package org.springframework.ai.dashscope.api;


import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.ai.dashscope.api.common.DashScopeConstants;
import org.springframework.ai.model.ModelDescription;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.util.api.ApiUtils;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.http.ResponseEntity;
import org.springframework.util.Assert;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * Single class implementation of the DashScope Chat Completion API and Embedding API.
 * <a href="https://help.aliyun.com/zh/dashscope/developer-reference/">DashScope Docs</a>
 *
 * @author Sober
 */
public class DashScopeApi {

	public static final DashScopeApi.ChatModel DEFAULT_CHAT_MODEL = ChatModel.QWEN_TURBO;

	private static final Predicate<String> SSE_DONE_PREDICATE = "[DONE]"::equals;

	private final RestClient restClient;

	private final WebClient webClient;


	/**
	 * Create a new chat completion api with default base URL.
	 *
	 * @param dashScopeToken DashScope apiKey.
	 */
	public DashScopeApi(String dashScopeToken) {
		this(DashScopeConstants.DEFAULT_BASE_URL, dashScopeToken);
	}

	/**
	 * Create a new chat completion api.
	 *
	 * @param baseUrl        api base URL.
	 * @param dashScopeToken DashScope apiKey.
	 */
	public DashScopeApi(String baseUrl, String dashScopeToken) {
		this(baseUrl, dashScopeToken, RestClient.builder(), WebClient.builder());
	}

	/**
	 * Create a new chat completion api.
	 *
	 * @param baseUrl           api base URL.
	 * @param dashScopeToken    DashScope apiKey.
	 * @param restClientBuilder RestClient builder.
	 * @param webClientBuilder  WebClient builder.
	 */
	public DashScopeApi(String baseUrl, String dashScopeToken, RestClient.Builder restClientBuilder, WebClient.Builder webClientBuilder) {
		this(baseUrl, dashScopeToken, restClientBuilder, webClientBuilder, RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER);
	}

	/**
	 * Create a new chat completion api.
	 *
	 * @param baseUrl              api base URL.
	 * @param dashScopeToken       DashScope apiKey.
	 * @param restClientBuilder    RestClient builder.
	 * @param webClientBuilder     WebClient builder.
	 * @param responseErrorHandler Response error handler.
	 */
	public DashScopeApi(String baseUrl, String dashScopeToken, RestClient.Builder restClientBuilder, WebClient.Builder webClientBuilder, ResponseErrorHandler responseErrorHandler) {
		this.restClient = restClientBuilder
				.baseUrl(baseUrl)
				.defaultHeaders(ApiUtils.getJsonContentHeaders(dashScopeToken))
				.defaultStatusHandler(responseErrorHandler)
				.build();

		this.webClient = webClientBuilder
				.baseUrl(baseUrl)
				.defaultHeaders(ApiUtils.getJsonContentHeaders(dashScopeToken))
				.build();
	}

	/**
	 * Creates a model response for the given chat conversation.
	 *
	 * @param chatRequest The chat completion request.
	 * @return Entity response with {@link ChatCompletion} as a body and HTTP status code and headers.
	 */
	public ResponseEntity<ChatCompletion> chatCompletionEntity(ChatCompletionRequest chatRequest) {

		Assert.notNull(chatRequest, "The request body can not be null.");

		return this.restClient.post()
				.uri("/text-generation/generation")
				.body(chatRequest)
				.retrieve()
				.toEntity(ChatCompletion.class);
	}

	private DashScopeStreamFunctionCallingHelper chunkMerger = new DashScopeStreamFunctionCallingHelper();

	/**
	 * Creates a streaming chat response for the given chat conversation.
	 *
	 * @param chatRequest The chat completion request. Must have the stream property set to true.
	 * @return Returns a {@link Flux} stream from chat completion chunks.
	 */
	public Flux<ChatCompletionChunk> chatCompletionStream(ChatCompletionRequest chatRequest) {

		Assert.notNull(chatRequest, "The request body can not be null.");

		AtomicBoolean isInsideTool = new AtomicBoolean(false);

		return this.webClient.post()
				.uri("/text-generation/generation")
				.header("X-DashScope-SSE", "enable")
				.body(Mono.just(chatRequest), ChatCompletionRequest.class)
				.retrieve()
				.bodyToFlux(String.class)
				// cancels the flux stream after the "[DONE]" is received.
				.takeUntil(SSE_DONE_PREDICATE)
				// filters out the "[DONE]" message.
				.filter(SSE_DONE_PREDICATE.negate())
				.map(content -> ModelOptionsUtils.jsonToObject(content, ChatCompletionChunk.class))
				// Detect is the chunk is part of a streaming function call.
//				.map(chunk -> {
//					if (this.chunkMerger.isStreamingToolFunctionCall(chunk)) {
//						isInsideTool.set(true);
//					}
//					return chunk;
//				})
				// Group all chunks belonging to the same function call.
				// Flux<ChatCompletionChunk> -> Flux<Flux<ChatCompletionChunk>>
				.windowUntil(chunk -> {
//					if (isInsideTool.get() && this.chunkMerger.isStreamingToolFunctionCallFinish(chunk)) {
//						isInsideTool.set(false);
//						return true;
//					}
					return !isInsideTool.get();
				})
				// Merging the window chunks into a single chunk.
				// Reduce the inner Flux<ChatCompletionChunk> window into a single Mono<ChatCompletionChunk>,
				// Flux<Flux<ChatCompletionChunk>> -> Flux<Mono<ChatCompletionChunk>>
				.concatMapIterable(window -> {
					Mono<ChatCompletionChunk> monoChunk = window.reduce(
							new ChatCompletionChunk(null, null, null, null),
							(previous, current) -> this.chunkMerger.merge(previous, current));
					return List.of(monoChunk);
				})
				.flatMap(mono -> mono);
	}


	/**
	 * DashScope Chat Completion Models:
	 * <a href="https://help.aliyun.com/zh/dashscope/developer-reference/model-square/">DashScope Chat Model</a>.
	 */
	public enum ChatModel implements ModelDescription {

		/**
		 * Qwen Turbo, a large-scale language model supporting inputs in various languages including Chinese and English.
		 * The model supports 8k tokens of context. To ensure normal usage and output, the API limits user input to 6k tokens.
		 */
		QWEN_TURBO("qwen-turbo"),

		/**
		 * Qwen Plus, an enhanced version of the large-scale language model supporting inputs in various languages including Chinese and English.
		 * The model supports 32k tokens of context. To ensure normal usage and output, the API limits user input to 30k tokens.
		 */
		QWEN_PLUS("qwen-plus"),

		/**
		 * Qwen Max, a super-large-scale language model at the trillion level, supporting inputs in various languages including Chinese and English.
		 * As the model upgrades, qwen-max will be continuously updated. If you wish to use a fixed version, please use historical snapshot versions.
		 * The model supports 8k tokens of context. To ensure normal usage and output, the API limits user input to 6k tokens.
		 */
		QWEN_MAX("qwen-max"),

		/**
		 * Qwen Max Long Context, a super-large-scale language model at the trillion level, supporting inputs in various languages including Chinese and English.
		 * The model supports 30k tokens of context. To ensure normal usage and output, the API limits user input to 28k tokens.
		 */
		QWEN_MAX_LONGCONTEXT("qwen-max-longcontext"),

		/**
		 * Qwen VL Plus, an enhanced large-scale vision-language model. It significantly improves detail recognition and text recognition capabilities,
		 * supporting ultra-high resolution images of over one million pixels and any aspect ratio. It provides superior performance on a wide range of visual tasks.
		 * The model supports 8k tokens of context. To ensure normal usage and output, the API limits user input to 6k tokens.
		 */
		QWEN_VL_PLUS("qwen-vl-plus"),

		/**
		 * Qwen VL Max, a super-large-scale vision-language model. Compared to the enhanced version, it further enhances visual reasoning and instruction following capabilities,
		 * providing higher levels of visual perception and cognition. It delivers optimal performance on more complex tasks.
		 * The model supports 8k tokens of context. To ensure normal usage and output, the API limits user input to 6k tokens.
		 */
		QWEN_VL_MAX("qwen-vl-max");

		public final String value;

		ChatModel(String value) {
			this.value = value;
		}

		public String getValue() {
			return value;
		}

		@Override
		public String getModelName() {
			return this.value;
		}
	}

	/**
	 * Represents a tool the model may call. Currently, only functions are supported as a tool.
	 *
	 * @param type     Represents the type of tools, currently only 'function' is supported
	 * @param function function definition
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record FunctionTool(
			@JsonProperty("type") Type type,
			@JsonProperty("function") Function function) {

		/**
		 * Create a tool of type 'function' and the given function definition.
		 *
		 * @param function function definition.
		 */
		@ConstructorBinding
		public FunctionTool(Function function) {
			this(Type.FUNCTION, function);
		}

		/**
		 * Create a tool of type 'function' and the given function definition.
		 */
		public enum Type {
			/**
			 * Function tool type.
			 */
			@JsonProperty("function") FUNCTION
		}

		/**
		 * Function 定义。
		 *
		 * @param description Represents the description of utility functions, enabling the model to determine when and how to invoke these functions.
		 * @param name        Represents the name of the tool function, which must consist of letters and numbers,
		 *                    and can include underscores and hyphens, with a maximum length of 64 characters.
		 * @param parameters  Represents the description of parameters for the tool.
		 *                    If the parameters parameter is empty, it indicates that the function has no input parameters.
		 */
		@JsonInclude(JsonInclude.Include.NON_NULL)
		public record Function(
				@JsonProperty("description") String description,
				@JsonProperty("name") String name,
				@JsonProperty("parameters") Map<String, Object> parameters) {

			/**
			 * Create tool function definition.
			 *
			 * @param description Represents the description of utility functions, enabling the model to determine when and how to invoke these functions.
			 * @param name        Represents the name of the tool function, which must consist of letters and numbers,
			 *                    and can include underscores and hyphens, with a maximum length of 64 characters.
			 * @param jsonSchema  A JSON representation describing the parameters of a tool.
			 */
			@ConstructorBinding
			public Function(String description, String name, String jsonSchema) {
				this(description, name, ModelOptionsUtils.jsonToMap(jsonSchema));
			}
		}
	}

	/**
	 * Creates a model response for the given chat conversation.
	 *
	 * @param model                    The ID of the model to be used.
	 * @param chatCompletionInput      Input model information.
	 * @param chatCompletionParameters Parameters for controlling model generation.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ChatCompletionRequest(
			@JsonProperty("model") String model,
			@JsonProperty("input") ChatCompletionInput chatCompletionInput,
			@JsonProperty("parameters") ChatCompletionParameters chatCompletionParameters) {

		/**
		 * Shortcut constructor for a chat completion request with the given messages for streaming.
		 *
		 * @param messages A list of messages comprising the conversation so far.
		 */
		public ChatCompletionRequest(List<ChatCompletionMessage> messages) {
			this(null, new ChatCompletionInput(messages), null);
		}

	}

	/**
	 * Input model information.
	 *
	 * @param chatCompletionMessages Represents the conversation history between the user and the model.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ChatCompletionInput(@JsonProperty("messages") List<ChatCompletionMessage> chatCompletionMessages) {
	}

	/**
	 * Represents the conversation history between the user and the model.
	 *
	 * @param role       Represents the role of the message sender. {@link Role}
	 * @param rawContent Represents the content of the message.
	 * @param name       The 'role' cannot be omitted when it is 'tool'.
	 *                   When the role is 'tool', it indicates that the current message is the result of a 'function_call'.
	 *                   The 'name' should match the function name from the 'tool_calls[i].function.name' parameter in the previous response,
	 *                   and the 'content' holds the output of the tool function.
	 * @param toolCalls  The tool calls generated by the model, such as function calls. Applicable only for {@link Role#ASSISTANT} role and null otherwise.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ChatCompletionMessage(
			@JsonProperty("role") Role role,
			@JsonProperty("content") Object rawContent,
			@JsonProperty("name") String name,
			@JsonProperty("tool_calls") List<ToolCall> toolCalls) {

		/**
		 * Get message content as String.
		 */
		public String content() {
			if (this.rawContent == null) {
				return null;
			}
			if (this.rawContent instanceof String text) {
				return text;
			}
			throw new IllegalStateException("The content is not a string!");
		}

		/**
		 * The role of the author of this message.
		 */
		public enum Role {
			/**
			 * System message.
			 */
			@JsonProperty("system") SYSTEM,
			/**
			 * User message.
			 */
			@JsonProperty("user") USER,
			/**
			 * Assistant message.
			 */
			@JsonProperty("assistant") ASSISTANT,
			/**
			 * Tool message.
			 */
			@JsonProperty("tool") TOOL
		}

		/**
		 * An array of content parts with a defined type.
		 * Each MediaContent can be of either "text" or "image_url" type. Not both.
		 *
		 * @param text     The text content of the message.
		 * @param imageUrl The image content of the message. You can pass multiple
		 *                 images by adding multiple image_url content parts. Image input is only
		 *                 supported when using the qwen-vl-plus/qwen-vl-max model.
		 */
		@JsonInclude(JsonInclude.Include.NON_NULL)
		public record MediaContent(
				@JsonProperty("text") String text,
				@JsonProperty("image") String imageUrl
		) {

			/**
			 * Shortcut constructor for a text content.
			 *
			 * @param type    The media type of the content.
			 * @param context The content of the media.
			 */
			public MediaContent(MediaEnum type, String context) {
				this(type.equals(MediaEnum.TEXT) ? context : null, type.equals(MediaEnum.IMAGE_URL) ? context : null);
			}


			/**
			 * The media type of the content.
			 */
			public enum MediaEnum {
				TEXT,
				IMAGE_URL;
			}
		}

		public ChatCompletionMessage(Object content, Role role) {
			this(role, content, null, null);
		}


	}

	/**
	 * The relevant tool call.
	 *
	 * @param type     The type of tool call the output is required for. For now, this is always function.
	 * @param function The function definition.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ToolCall(
			@JsonProperty("type") String type,
			@JsonProperty("function") ChatCompletionFunction function
	) {
	}

	/**
	 * The function definition.
	 *
	 * @param name      The name of the function.
	 * @param arguments The arguments that the model expects you to pass to the function.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ChatCompletionFunction(
			@JsonProperty("name") String name,
			@JsonProperty("arguments") String arguments) {
	}


	/**
	 * Parameters for controlling model generation.
	 *
	 * @param temperature       Controls the degree of randomness and diversity. Specifically, the temperature value
	 *                          regulates the extent of smoothing applied to the probability distribution of each candidate word
	 *                          during text generation. A higher temperature value reduces the peaks of the probability distribution,
	 *                          allowing for more low-probability words to be selected, resulting in more diverse outcomes;
	 *                          whereas a lower temperature value enhances the peaks of the probability distribution, making high-probability
	 *                          words more likely to be chosen, leading to more deterministic results.
	 *                          Value range: [0, 2), it is not recommended to set this value to 0 as it would be meaningless.
	 * @param topP              A probability threshold used in nucleus sampling.
	 *                          Tokens with cumulative probabilities above this threshold are selected for sampling.
	 *                          Higher values increase randomness while lower values decrease it. Should not exceed 1.0.
	 * @param resultFormat      Determines the format of the result returned by the model. It can be set to 'text' or 'message'. Default is 'text'.
	 * @param seed              An unsigned 64-bit integer used as a random seed to control the randomness of the model's output.
	 *                          When using a specific seed, the model attempts to generate similar results, but they might not be exactly identical due to the nature of the model.
	 * @param maxTokens         Sets the maximum number of tokens that the model will generate.
	 * @param topK              Specifies the size of the candidate set from which tokens are sampled.
	 *                          Larger values increase randomness while smaller values increase determinism.
	 * @param repetitionPenalty Controls the repetition of sequences within the generated text. Higher values reduce repetition.
	 *                          There's no strict range, but 1.0 means no penalty is applied.
	 * @param presencePenalty   Affects the overall sequence repetition. Higher values reduce repetition, and it ranges from -2.0 to 2.0.
	 * @param stop              The stop parameter is used for precise control over the content generation process, automatically halting
	 *                          when the generated content is about to include a specified string or token_id, ensuring that the generated
	 *                          content does not contain the specified content.
	 *                          It is not allowed to input both token_id and strings as elements simultaneously, for example, you cannot
	 *                          specify stop as ["hello", 104307].
	 * @param enableSearch      The model comes with built-in internet search services. This parameter controls whether the model should
	 *                          refer to internet search results while generating text.
	 * @param incrementalOutput Controls whether to enable incremental output in streaming output mode, i.e., whether subsequent output
	 *                          includes previously output content.
	 *                          Setting it to True will enable incremental output mode, where subsequent outputs do not include already
	 *                          output content, requiring you to concatenate the overall output yourself; setting it to False will include
	 *                          previously output content.
	 * @param tools             Specifies a list of tools that the model can call upon. When multiple tools are input, the model selects one to generate results.
	 * @param toolChoice        When using the tools parameter, this controls the model's invocation of a specified tool. Possible values are:
	 *                          'none' indicates no tool should be called. When the tools parameter is empty, the default value is 'none'.
	 *                          'auto' allows the model to decide whether to call a tool, which may result in calling or not calling a tool.
	 *                          When the tools parameter is not empty, the default value is 'auto'.
	 *                          An object structure can specify the model to call a specific tool. For example, {"type": "function",
	 *                          "function": {"name": "user_function"}}
	 *                          Currently, 'type' only supports 'function'
	 *                          'function.name' represents the name of the tool expected to be called
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ChatCompletionParameters(
			@JsonProperty("temperature") Float temperature,
			@JsonProperty("top_p") Float topP,
			@JsonProperty("result_format") String resultFormat,
			@JsonProperty("seed") Integer seed,
			@JsonProperty("max_tokens") String maxTokens,
			@JsonProperty("top_k") Integer topK,
			@JsonProperty("repetition_penalty") Float repetitionPenalty,
			@JsonProperty("presence_penalty") Float presencePenalty,
			@JsonProperty("stop") List<String> stop,
			@JsonProperty("enable_search") Boolean enableSearch,
			@JsonProperty("incremental_output") Boolean incrementalOutput,
			@JsonProperty("tools") List<DashScopeApi.FunctionTool> tools,
			@JsonProperty("tool_choice") String toolChoice) {

		public ChatCompletionParameters(
				Float temperature,
				Float topP,
				String resultFormat,
				Integer seed,
				String maxTokens,
				Integer topK,
				Float repetitionPenalty,
				Float presencePenalty,
				List<String> stop,
				Boolean enableSearch,
				Boolean incrementalOutput,
				List<DashScopeApi.FunctionTool> tools,
				String toolChoice) {
			this.temperature = temperature;
			this.topP = topP;
			this.resultFormat = resultFormat != null ? resultFormat : ResultFormatEnum.MESSAGE.getValue();
			this.seed = seed;
			this.maxTokens = maxTokens;
			this.topK = topK;
			this.repetitionPenalty = repetitionPenalty;
			this.presencePenalty = presencePenalty;
			this.stop = stop;
			this.enableSearch = enableSearch;
			this.incrementalOutput = incrementalOutput != null ? incrementalOutput : Boolean.TRUE;
			this.tools = tools;
			this.toolChoice = toolChoice;
		}

		public enum ResultFormatEnum {
			TEXT("text"),
			MESSAGE("message");

			private final String value;

			ResultFormatEnum(String value) {
				this.value = value;
			}

			public String getValue() {
				return value;
			}
		}

	}


	/**
	 * Represents a chat completion response returned by model, based on the provided input.
	 *
	 * @param requestId The system unique code for this request.
	 * @param output    The output results relevant to this call.
	 * @param usage     The token information used for this call.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ChatCompletion(
			@JsonProperty("request_id") String requestId,
			@JsonProperty("output") Output output,
			@JsonProperty("usage") Usage usage
	) {
	}

	/**
	 * Represents a chat completion response returned by model, based on the provided input.
	 *
	 * @param requestId The system unique code for this request.
	 * @param output    The output results relevant to this call.
	 * @param usage     The token information used for this call.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ChatCompletionChunk(
			@JsonProperty("request_id") String requestId,
			@JsonProperty("output") Output output,
			@JsonProperty("usage") Usage usage,
			@JsonProperty("object") String object
	) {

	}

	/**
	 * The reason the model stopped generating tokens.
	 */
	public enum ChatCompletionFinishReason {
		/**
		 * The model hit a natural stop point or a provided stop sequence.
		 */
		@JsonProperty("stop") STOP,
		/**
		 * The maximum number of tokens specified in the request was reached.
		 */
		@JsonProperty("length") LENGTH,
		/**
		 * During the process of model generation.
		 */
		@JsonProperty("null") NULL,
		/**
		 * The model called a tool.
		 */
		@JsonProperty("tool_calls") TOOL_CALLS
	}

	/**
	 * The output results relevant to this call.
	 *
	 * @param text         This field is returned when the result_format is set to text.
	 * @param finishReason There are three scenarios:
	 *                     It is null while generation is in progress.
	 *                     It becomes stop when generation ends due to a stop token.
	 *                     It turns into length if the end of generation is caused by exceeding the maximum allowed length.
	 *                     This field is returned when the result_format is set to text.
	 * @param choices      This field is returned when the result_format is set to message.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Output(
			@JsonProperty("text") String text,
			@JsonProperty("finish_reason") String finishReason,
			@JsonProperty("choices") List<Choice> choices
	) {

	}

	/**
	 * The output content returned when the result_format is set to message.
	 *
	 * @param finishReason There are three scenarios:
	 *                     It is null while generation is in progress.
	 *                     It becomes stop when generation ends due to a stop token.
	 *                     It turns into length if the end of generation is caused by exceeding the maximum allowed length.
	 *                     This field is returned when the result_format is set to text.
	 * @param message      A chat completion message generated by the model.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Choice(
			@JsonProperty("finish_reason") ChatCompletionFinishReason finishReason,
			@JsonProperty("message") ChatCompletionMessage message
	) {

	}

	/**
	 * The token information used for this call.
	 *
	 * @param outputTokens The number of tokens used for the output.
	 * @param inputTokens  The number of tokens used for the input.
	 * @param totalTokens  The total number of tokens used for this call.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Usage(
			@JsonProperty("output_tokens") Integer outputTokens,
			@JsonProperty("input_tokens") Integer inputTokens,
			@JsonProperty("total_tokens") Integer totalTokens
	) {

	}

}
