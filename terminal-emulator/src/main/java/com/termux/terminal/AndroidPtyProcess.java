package com.termux.terminal;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** WawLink adapter: exposes the pinned Termux JNI PTY as a runtime-owned Process.
 * ParcelFileDescriptor owns each fd exactly once; no hidden-API reflection is used. */
public final class AndroidPtyProcess extends Process implements AutoCloseable {
    private final int childPid;
    private final ParcelFileDescriptor master;
    private final InputStream input;
    private final OutputStream output;
    private final CountDownLatch exited = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile int status;

    public AndroidPtyProcess(ProcessBuilder builder) throws IOException {
        String[] command = builder.command().toArray(new String[0]);
        String[] environment = builder.environment().entrySet().stream()
            .map(entry -> entry.getKey() + "=" + entry.getValue()).toArray(String[]::new);
        int[] pid = new int[1];
        int fd = JNI.createSubprocess(command[0], builder.directory().getAbsolutePath(), command,
            environment, pid, 24, 80, 0, 0);
        childPid = pid[0];
        master = ParcelFileDescriptor.adoptFd(fd);
        try {
            input = new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(master.getFileDescriptor()));
            output = new ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(master.getFileDescriptor()));
        } catch (IOException failure) {
            signal(OsConstants.SIGKILL);
            master.close();
            JNI.waitFor(childPid);
            throw failure;
        }
        Thread waiter = new Thread(() -> {
            status = JNI.waitFor(childPid);
            exited.countDown();
        }, "WawLinkPtyWait-" + childPid);
        waiter.setDaemon(true);
        waiter.start();
    }

    public void resize(int columns, int rows, int cellWidth, int cellHeight) {
        if (!closed.get() && isAlive()) JNI.setPtyWindowSize(master.getFd(), rows, columns, cellWidth, cellHeight);
    }

    @Override public InputStream getInputStream() { return input; }
    @Override public OutputStream getOutputStream() { return output; }
    @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
    @Override public boolean isAlive() { return exited.getCount() != 0; }
    @Override public int waitFor() throws InterruptedException { exited.await(); return status; }
    @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException { return exited.await(timeout, unit); }
    @Override public int exitValue() {
        if (isAlive()) throw new IllegalThreadStateException("PTY process is still running");
        return status;
    }
    @Override public void destroy() { signal(OsConstants.SIGTERM); }
    @Override public Process destroyForcibly() { signal(OsConstants.SIGKILL); return this; }
    public long pid() { return childPid; }
    @Override public String toString() { return "AndroidPtyProcess[pid=" + childPid + "]"; }

    private void signal(int signal) {
        if (!isAlive()) return;
        try { Os.kill(childPid, signal); }
        catch (ErrnoException failure) {
            if (failure.errno != OsConstants.ESRCH) throw new IllegalStateException("Cannot signal PTY process", failure);
        }
    }

    @Override public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        try { input.close(); }
        finally { try { output.close(); } finally { master.close(); } }
    }
}
