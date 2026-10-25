import { AgentRegistry, MAIN_AGENT_ID } from '@oh-my-pi/pi-coding-agent/registry/agent-registry';
import { createRuntimeExplorer } from './explorer-runtime.mjs';
import { forkHistory, historyBranch, historyDetail, historyFind, historyPosition, historySnapshot, reanswer, requireIdle, restoredNavigation } from './explorer-history.mjs';
import { safeReason } from './diagnostics.mjs';

export function explorerError(error, request) {
  return safeReason(error?.message ?? error, request)
    .replace(/((?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret)\s*[:=]\s*)[^\s,;]+/gi, '$1[redacted]');
}

/** Uses the current native AgentSession, never an emulated navigation context. */
export function createSessionExplorer(pi, getContext) {
  let navigation;
  const mainSession = () => {
    const context = getContext();
    if (!context) throw Error('Native session is unavailable');
    const sessionId = context.sessionManager.getSessionId();
    const main = AgentRegistry.global().get(MAIN_AGENT_ID)?.session;
    if (!main || main.sessionManager.getSessionId() !== sessionId)
      throw Error('Native main AgentSession is unavailable for this session');
    return main;
  };
  const runtime = createRuntimeExplorer(mainSession, getContext);
  return async request => {
    try {
      const context = getContext();
      if (!context) throw Error('Native session is unavailable');
      const sessionId = context.sessionManager.getSessionId();
      if (request.sessionId && request.sessionId !== sessionId) throw Error('Stale native session target');
      let data;
      switch (request.op) {
        case 'explorer_history': data = historySnapshot(context.sessionManager, request); break;
        case 'explorer_history_detail': data = historyDetail(context.sessionManager, request); break;
        case 'explorer_history_find': data = historyFind(context.sessionManager, request); break;
        case 'explorer_history_branch': data = historyBranch(context.sessionManager); break;
        case 'explorer_label': {
          if (typeof request.entryId !== 'string' || (request.label != null && typeof request.label !== 'string')) throw Error('Invalid native label');
          const session = mainSession();
          session.sessionManager.appendLabelChange(request.entryId, request.label ?? undefined);
          await session.sessionManager.flush();
          data = historyPosition(session.sessionManager); break;
        }
        case 'explorer_navigate': {
          const session = mainSession();
          requireIdle(session, 'navigate the history tree');
          if (navigation) throw Error('Native history navigation is already running');
          navigation = { session, sessionId };
          try {
            const result = await session.navigateTree(request.entryId, { summarize: request.summary?.enabled === true,
              customInstructions: request.summary?.instructions ?? undefined });
            await session.sessionManager.flush();
            data = { ...restoredNavigation(result), ...historyPosition(session.sessionManager) };
          } finally { navigation = undefined; }
          break;
        }
        case 'explorer_cancel_navigation':
          if (navigation?.sessionId === sessionId) navigation.session.abortBranchSummary();
          data = { cancelled: !!navigation && navigation.sessionId === sessionId }; break;
        case 'explorer_reanswer': data = await reanswer(mainSession(), request); break;
        case 'explorer_fork': data = await forkHistory(mainSession(), request); break;
        default: data = await runtime(request);
      }
      return { success: true, ...data };
    } catch (error) { return { success: false, error: explorerError(error, request) }; }
  };
}
