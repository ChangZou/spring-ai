package org.springframework.ai.dashscope;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.dashscope.api.DashScopeApi;
import org.springframework.ai.dashscope.api.DashScopeApi.*;
import org.springframework.ai.dashscope.api.DashScopeApi.ChatCompletionMessage.MediaContent;
import org.springframework.ai.dashscope.api.DashScopeApi.ChatCompletionMessage.Role;
import org.springframework.ai.dashscope.metadata.DashScopChatResponseMetadata;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.model.function.AbstractFunctionCallSupport;
import org.springframework.ai.model.function.FunctionCallbackContext;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MimeType;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ChatModel} and {@link StreamingChatModel} implementation for {@literal DashScope}
 * backed by {@link DashScopeApi}.
 *
 * @author Sober
 * @see ChatModel
 * @see StreamingChatModel
 * @see DashScopeApi
 */
public class DashScopeChatModel extends
		AbstractFunctionCallSupport<DashScopeApi.ChatCompletionMessage, ChatCompletionRequest, ResponseEntity<ChatCompletion>>
		implements ChatModel {


	private static final Logger logger = LoggerFactory.getLogger(DashScopeChatModel.class);

	/**
	 * The default options used for the chat completion requests.
	 */
	private final DashScopeChatOptions defaultOptions;


	/**
	 * The retry template used to retry the DashScope API calls.
	 */
	public final RetryTemplate retryTemplate;

	/**
	 * Low-level access to the DashScope API.
	 */
	private final DashScopeApi dashScopeApi;

	/**
	 * Creates an instance of the DashScopeChatModel.
	 *
	 * @param dashScopeApi The DashScopeApi instance to be used for interacting with the
	 *                   DashScope Chat API.
	 * @throws IllegalArgumentException if zhiPuAiApi is null
	 */
	public DashScopeChatModel(DashScopeApi dashScopeApi) {
		this(dashScopeApi,
				DashScopeChatOptions.builder()
						.withModel(DashScopeApi.DEFAULT_CHAT_MODEL)
						.withTemperature(0.7f)
						.build());
	}

	/**
	 * Initializes an instance of the DashScopeChatModel.
	 *
	 * @param zhiPuAiApi The ZhiPuAiApi instance to be used for interacting with the
	 *                   ZhiPuAI Chat API.
	 * @param options    The ZhiPuAiChatOptions to configure the chat model.
	 */
	public DashScopeChatModel(DashScopeApi zhiPuAiApi, DashScopeChatOptions options) {
		this(zhiPuAiApi, options, null, RetryUtils.DEFAULT_RETRY_TEMPLATE);
	}

	/**
	 * Initializes a new instance of the DashScopeChatModel.
	 *
	 * @param dashScopeApi            The DashScopeApi instance to be used for interacting with the
	 *                                ZhiPuAI Chat API.
	 * @param options                 The DashScopeChatOptions to configure the chat model.
	 * @param functionCallbackContext The function callback context.
	 * @param retryTemplate           The retry template.
	 */
	public DashScopeChatModel(DashScopeApi dashScopeApi, DashScopeChatOptions options,
							  FunctionCallbackContext functionCallbackContext, RetryTemplate retryTemplate) {
		super(functionCallbackContext);
		Assert.notNull(dashScopeApi, "DashScopeApi must not be null");
		Assert.notNull(options, "Options must not be null");
		Assert.notNull(retryTemplate, "RetryTemplate must not be null");
		this.dashScopeApi = dashScopeApi;
		this.defaultOptions = options;
		this.retryTemplate = retryTemplate;
	}


	@Override
	public ChatResponse call(Prompt prompt) {

		ChatCompletionRequest request = createRequest(prompt, false);

		return this.retryTemplate.execute(ctx -> {

			ResponseEntity<ChatCompletion> completionEntity = this.callWithFunctionSupport(request);

			var chatCompletion = completionEntity.getBody();
			if (chatCompletion == null) {
				logger.warn("No chat completion returned for prompt: {}", prompt);
				return new ChatResponse(List.of());
			}

			List<Generation> generations;
			if (request.chatCompletionParameters().resultFormat() != null && request.chatCompletionParameters().resultFormat().equals("message")) {
				generations = chatCompletion.output().choices().stream()
						.map(choice -> new Generation(choice.message().content(), toMap(chatCompletion.requestId(), choice))
								.withGenerationMetadata(ChatGenerationMetadata.from(choice.finishReason().name(), null)))
						.toList();
			} else {
				generations = List.of(
						new Generation(chatCompletion.output().text(), toMap(chatCompletion.requestId(), chatCompletion.output().text(), chatCompletion.output().finishReason()))
								.withGenerationMetadata(ChatGenerationMetadata.from(chatCompletion.output().finishReason(), null))
				);
			}
			return new ChatResponse(generations);
		});
	}

	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {

		ChatCompletionRequest request = createRequest(prompt, true);

		return this.retryTemplate.execute(ctx -> {

			Flux<ChatCompletionChunk> completionChunks = this.dashScopeApi.chatCompletionStream(request);

			// For chunked responses, only the first chunk contains the choice role.
			// The rest of the chunks with same ID share the same role.
			ConcurrentHashMap<String, String> roleMap = new ConcurrentHashMap<>();

			// Convert the ChatCompletionChunk into a ChatCompletion to be able to reuse
			// the function call handling logic.
			return completionChunks.map(chunk -> chunkToChatCompletion(chunk))
					.switchMap(
							cc -> handleFunctionCallOrReturnStream(request, Flux.just(ResponseEntity.of(Optional.of(cc)))))
					.map(ResponseEntity::getBody)
					.map(chatCompletion -> {
						try {
							@SuppressWarnings("null")
							String id = chatCompletion.requestId();

							List<Generation> generations = chatCompletion.output().choices().stream().map(choice -> {
								if (choice.message().role() != null) {
									roleMap.putIfAbsent(id, choice.message().role().name());
								}
								String finish = (choice.finishReason() != null ? choice.finishReason().name() : "");
								var generation = new Generation(choice.message().content(),
										Map.of("request_id", id, "role", roleMap.getOrDefault(id, ""), "finishReason", finish));
								if (choice.finishReason() != null) {
									generation = generation.withGenerationMetadata(
											ChatGenerationMetadata.from(choice.finishReason().name(), null));
								}
								return generation;
							}).toList();

							if (chatCompletion.usage() != null) {
								return new ChatResponse(generations, DashScopChatResponseMetadata.from(chatCompletion));
							}
							else {
								return new ChatResponse(generations);
							}
						}
						catch (Exception e) {
							logger.error("Error processing chat completion", e);
							return new ChatResponse(List.of());
						}

					});
		});
	}

	/**
	 * Convert the ChatCompletionChunk into a ChatCompletion. The Usage is set to null.
	 * @param chunk the ChatCompletionChunk to convert
	 * @return the ChatCompletion
	 */
	private ChatCompletion chunkToChatCompletion(ChatCompletionChunk chunk) {
		List<Choice> choices = chunk.output().choices()
				.stream()
				.map(cc -> new Choice(cc.finishReason(), cc.message()))
				.toList();

		return new ChatCompletion(chunk.requestId(), new Output(null, null, choices), null);
	}

	private Map<String, Object> toMap(String requestId, String text, String finishReason) {
		Map<String, Object> map = new HashMap<>();
		if (text != null) {
			map.put("role", Role.ASSISTANT);
		}
		if (finishReason != null) {
			map.put("finishReason", finishReason);
		}
		map.put("request_id", requestId);
		return map;
	}

	private Map<String, Object> toMap(String requestId, Choice choice) {
		Map<String, Object> map = new HashMap<>();

		var message = choice.message();
		if (message.role() != null) {
			map.put("role", message.role().name());
		}
		if (choice.finishReason() != null) {
			map.put("finishReason", choice.finishReason());
		}
		map.put("request_id", requestId);
		return map;
	}

	private ChatCompletionRequest createRequest(Prompt prompt, boolean stream) {

		Set<String> functionsForThisRequest = new HashSet<>();

		List<DashScopeApi.ChatCompletionMessage> chatCompletionMessages = prompt.getInstructions().stream().map(m -> {
			Object content;
			if (CollectionUtils.isEmpty(m.getMedia())) {
				content = m.getContent();
			}
			else {
				List<MediaContent> contentList = new ArrayList<>(List.of(new MediaContent(MediaContent.MediaEnum.TEXT, m.getContent())));

				contentList.addAll(m.getMedia()
						.stream()
						.map(media -> new MediaContent(MediaContent.MediaEnum.TEXT, this.fromMediaData(media.getMimeType(), media.getData())))
						.toList());

				content = contentList;
			}

			return new DashScopeApi.ChatCompletionMessage(content, Role.valueOf(m.getMessageType().name()));
		}).toList();

		ChatCompletionRequest request = new ChatCompletionRequest(chatCompletionMessages);

		if (prompt.getOptions() != null) {
			DashScopeChatOptions updatedRuntimeOptions = ModelOptionsUtils.copyToTarget(prompt.getOptions(),
					ChatOptions.class, DashScopeChatOptions.class);

			Set<String> promptEnabledFunctions = this.handleFunctionCallbackConfigurations(updatedRuntimeOptions,
					IS_RUNTIME_CALL);
			functionsForThisRequest.addAll(promptEnabledFunctions);

			request = ModelOptionsUtils.merge(updatedRuntimeOptions, request, ChatCompletionRequest.class);
		}

		if (this.defaultOptions != null) {

			Set<String> defaultEnabledFunctions = this.handleFunctionCallbackConfigurations(this.defaultOptions,
					!IS_RUNTIME_CALL);

			functionsForThisRequest.addAll(defaultEnabledFunctions);

			request = ModelOptionsUtils.merge(request, this.defaultOptions, ChatCompletionRequest.class);
		}

		// Add the enabled functions definitions to the request's tools parameter.
		if (!CollectionUtils.isEmpty(functionsForThisRequest)) {

			request = ModelOptionsUtils.merge(
					DashScopeChatOptions.builder().withTools(this.getFunctionTools(functionsForThisRequest)).build(),
					request, ChatCompletionRequest.class);
		}
		return request;
	}

	private List<DashScopeApi.FunctionTool> getFunctionTools(Set<String> functionNames) {
		return this.resolveFunctionCallbacks(functionNames).stream().map(functionCallback -> {
			var function = new DashScopeApi.FunctionTool.Function(functionCallback.getDescription(),
					functionCallback.getName(), functionCallback.getInputTypeSchema());
			return new DashScopeApi.FunctionTool(function);
		}).toList();
	}

	private String fromMediaData(MimeType mimeType, Object mediaContentData) {
		if (mediaContentData instanceof byte[] bytes) {
			// Assume the bytes are an image. So, convert the bytes to a base64 encoded
			// following the prefix pattern.
			return String.format("data:%s;base64,%s", mimeType.toString(), Base64.getEncoder().encodeToString(bytes));
		}
		else if (mediaContentData instanceof String text) {
			// Assume the text is a URLs or a base64 encoded image prefixed by the user.
			return text;
		}
		else {
			throw new IllegalArgumentException(
					"Unsupported media data type: " + mediaContentData.getClass().getSimpleName());
		}
	}


	@Override
	public ChatOptions getDefaultOptions() {
		return null;
	}

	@Override
	protected ChatCompletionRequest doCreateToolResponseRequest(ChatCompletionRequest previousRequest, DashScopeApi.ChatCompletionMessage responseMessage, List<DashScopeApi.ChatCompletionMessage> conversationHistory) {
		return null;
	}

	@Override
	protected List<DashScopeApi.ChatCompletionMessage> doGetUserMessages(ChatCompletionRequest request) {
		return List.of();
	}

	@Override
	protected DashScopeApi.ChatCompletionMessage doGetToolResponseMessage(ResponseEntity<ChatCompletion> response) {
		return null;
	}

	@Override
	protected ResponseEntity<ChatCompletion> doChatCompletion(ChatCompletionRequest request) {
		return this.dashScopeApi.chatCompletionEntity(request);
	}

	@Override
	protected Flux<ResponseEntity<ChatCompletion>> doChatCompletionStream(ChatCompletionRequest request) {
		return this.dashScopeApi.chatCompletionStream(request)
				.map(this::chunkToChatCompletion)
				.map(Optional::ofNullable)
				.map(ResponseEntity::of);
	}

	@Override
	protected boolean isToolFunctionCall(ResponseEntity<ChatCompletion> response) {
		return false;
	}
}
