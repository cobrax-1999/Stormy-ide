# Continuity Ledger - CodeX AI Coding Agent

## Goal (incl. success criteria):
Fix performance issues, UI/UX problems, and model compatibility in the CodeX Android app to make it production-ready.

**Success criteria**:
- [x] Working bubble replaced with minimal indicator
- [x] Tool calls properly rendered (not as text with emojis)
- [x] No lag during streaming in agent mode
- [x] All models function properly with tool calling
- [x] Debug logs show raw unformatted server responses

## Constraints/Assumptions:
- Must maintain backward compatibility
- Cannot break existing functionality
- Kotlin/Jetpack Compose codebase
- DeepInfra API provider (free, no API key required)
- File size limits: 500-1000 lines per file maximum

## Key Decisions:
1. **Debounce Interval**: Changed from 50ms to 100ms (10 FPS) for better performance
2. **Streaming Parsing**: Simplified parsing during streaming to reduce CPU load
3. **Tool Call Fallback**: Added text-based tool call parser for models without native function calling
4. **Debug Logging**: Separate raw SSE log file (`.codex/raw_sse.log`) for unformatted server responses
5. **Generating Indicator**: Minimal inline design instead of full bubble

## State:

### Done (Current Session - 2026-01-06):
- [x] Removed Working bubble, implemented minimal generating indicator
- [x] Fixed tool call text rendering with fallback parser (`parseTextBasedToolCalls()`)
- [x] Improved streaming performance (debounce 100ms, simplified parsing)
- [x] Fixed model compatibility (improved system prompt, fallback parser)
- [x] Fixed debug logs (raw SSE logging to `.codex/raw_sse.log`)
- [x] Created CLAUDE.md documentation
- [x] Updated CONTINUITY.md

### Done (Previous Session):
- [x] Fixed build error in EditorViewModel.kt:1037
- [x] Created CodeDiffTools.kt - Production-grade diff tools
- [x] Created ShellToolExecutor.kt - Safe shell execution
- [x] Created ExtendedToolExecutor.kt - Advanced tool executor
- [x] Created ToolArgumentValidator.kt - Comprehensive validation
- [x] Added 15+ new tool definitions

### Now:
- All requested tasks completed

### Next:
- Testing in real-world scenarios
- Monitor for any edge cases in tool call parsing
- Consider additional model-specific optimizations

## Open Questions:
- None currently

## Working Set (Current Session):

### Files Modified:
- `app/src/main/java/com/codex/stormy/ui/components/message/AiMessageContent.kt`
  - Added `MinimalGeneratingIndicator`, `InlineGeneratingIndicator`, `GeneratingDots`
  - Added `parseStreamingContent()` for lightweight streaming parsing
  - Modified `AiMessageContent()` to use conditional parsing

- `app/src/main/java/com/codex/stormy/ui/screens/editor/EditorViewModel.kt`
  - Added `parseTextBasedToolCalls()`, `parseToolCallJson()`, `parseFunctionArguments()`
  - Added `logDebugRawSSE()` for raw SSE logging
  - Modified streaming event handler to handle `RawSSE` events
  - Changed debounce interval from 50ms to 100ms

- `app/src/main/java/com/codex/stormy/data/ai/DeepInfraProvider.kt`
  - Added `StreamEvent.RawSSE` event type
  - Modified `onEvent()` to emit raw SSE data

- `app/src/main/java/com/codex/stormy/data/repository/AiRepository.kt`
  - Updated system prompt to emphasize native function calling

### Files Created:
- `CLAUDE.md` - Project knowledge base
- `CONTINUITY.md` - Session continuity ledger (this file)

### Key Classes:
- `EditorViewModel` - Main ViewModel, handles streaming, tool execution
- `AiMessageContent` - Composable for rendering AI messages
- `DeepInfraProvider` - SSE streaming implementation
- `StormyTools` - Tool definitions
