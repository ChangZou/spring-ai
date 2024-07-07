package org.springframework.ai.dashscope.api;

import org.springframework.ai.dashscope.api.DashScopeApi.ChatCompletionFunction;
import org.springframework.ai.dashscope.api.DashScopeApi.ChatCompletionMessage;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;

import java.util.ArrayList;
import java.util.List;

import static org.springframework.ai.dashscope.api.DashScopeApi.*;

/**
 * Helper class to support Streaming function calling.
 * It can merge the streamed ChatCompletionChunk in case of function calling message.
 *
 * @author Sober
 */
public class DashScopeStreamFunctionCallingHelper {

	/**
	 * Merge the previous and current ChatCompletionChunk into a single one.
	 *
	 * @param previous the previous ChatCompletionChunk
	 * @param current  the current ChatCompletionChunk
	 * @return the merged ChatCompletionChunk
	 */
	public ChatCompletionChunk merge(ChatCompletionChunk previous, ChatCompletionChunk current) {

		if (previous == null) {
			return current;
		}

		String id = (current.requestId() != null ? current.requestId() : previous.requestId());
		String object = (current.object() != null ? current.object() : previous.object());
		Usage usage = (current.usage() != null ? current.usage() : previous.usage());

		Choice previousChoice0 = (ObjectUtils.isEmpty(previous.output()) || CollectionUtils.isEmpty(previous.output().choices()) ? null : previous.output().choices().get(0));
		Choice currentChoice0 = (ObjectUtils.isEmpty(current.output()) || CollectionUtils.isEmpty(current.output().choices()) ? null : current.output().choices().get(0));

		Choice choice = merge(previousChoice0, currentChoice0);
		List<Choice> chunkChoices = choice == null ? List.of() : List.of(choice);
		return new ChatCompletionChunk(id, new Output(null, null, chunkChoices), usage, object);
	}

	private Choice merge(Choice previous, Choice current) {
		if (previous == null) {
			return current;
		}

		ChatCompletionFinishReason finishReason = (current.finishReason() != null ? current.finishReason()
				: previous.finishReason());

		ChatCompletionMessage message = merge(previous.message(), current.message());

		return new Choice(finishReason, message);
	}

	private ChatCompletionMessage merge(ChatCompletionMessage previous, ChatCompletionMessage current) {
		String content = (current.content() != null ? current.content()
				: "" + ((previous.content() != null) ? previous.content() : ""));
		ChatCompletionMessage.Role role = (current.role() != null ? current.role() : previous.role());
		role = (role != null ? role : ChatCompletionMessage.Role.ASSISTANT);
		String name = (current.name() != null ? current.name() : previous.name());

		List<ToolCall> toolCalls = new ArrayList<>();
		ToolCall lastPreviousTooCall = null;
		if (previous.toolCalls() != null) {
			lastPreviousTooCall = previous.toolCalls().get(previous.toolCalls().size() - 1);
			if (previous.toolCalls().size() > 1) {
				toolCalls.addAll(previous.toolCalls().subList(0, previous.toolCalls().size() - 1));
			}
		}
		if (current.toolCalls() != null) {
			if (current.toolCalls().size() > 1) {
				throw new IllegalStateException("Currently only one tool call is supported per message!");
			}
			var currentToolCall = current.toolCalls().iterator().next();
			if (currentToolCall.function().name() != null) {
				if (lastPreviousTooCall != null) {
					toolCalls.add(lastPreviousTooCall);
				}
				toolCalls.add(currentToolCall);
			} else {
				toolCalls.add(merge(lastPreviousTooCall, currentToolCall));
			}
		} else {
			if (lastPreviousTooCall != null) {
				toolCalls.add(lastPreviousTooCall);
			}
		}
		return new ChatCompletionMessage(role, content, name, toolCalls);
	}

	private ToolCall merge(ToolCall previous, ToolCall current) {
		if (previous == null) {
			return current;
		}
		String type = (current.type() != null ? current.type() : previous.type());
		ChatCompletionFunction function = merge(previous.function(), current.function());
		return new ToolCall(type, function);
	}

	private ChatCompletionFunction merge(ChatCompletionFunction previous, ChatCompletionFunction current) {
		if (previous == null) {
			return current;
		}
		String name = (current.name() != null ? current.name() : previous.name());
		StringBuilder arguments = new StringBuilder();
		if (previous.arguments() != null) {
			arguments.append(previous.arguments());
		}
		if (current.arguments() != null) {
			arguments.append(current.arguments());
		}
		return new ChatCompletionFunction(name, arguments.toString());
	}

	/**
	 * @param chatCompletion the ChatCompletionChunk to check
	 * @return true if the ChatCompletionChunk is a streaming tool function call.
	 */
	public boolean isStreamingToolFunctionCall(ChatCompletionChunk chatCompletion) {

		if (chatCompletion == null || CollectionUtils.isEmpty(chatCompletion.output().choices())) {
			return false;
		}

		var choice = chatCompletion.output().choices().get(0);
		if (choice == null || choice.message() == null) {
			return false;
		}
		return !CollectionUtils.isEmpty(choice.message().toolCalls());
	}

	/**
	 * @param chatCompletion the ChatCompletionChunk to check
	 * @return true if the ChatCompletionChunk is a streaming tool function call and it is
	 * the last one.
	 */
	public boolean isStreamingToolFunctionCallFinish(ChatCompletionChunk chatCompletion) {

		if (chatCompletion == null || CollectionUtils.isEmpty(chatCompletion.output().choices())) {
			return false;
		}

		var choice = chatCompletion.output().choices().get(0);
		if (choice == null || choice.message() == null) {
			return false;
		}
		return choice.finishReason() == ChatCompletionFinishReason.TOOL_CALLS;
	}

}