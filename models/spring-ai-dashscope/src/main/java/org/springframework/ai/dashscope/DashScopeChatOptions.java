package org.springframework.ai.dashscope;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.dashscope.api.DashScopeApi;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.model.function.FunctionCallingOptions;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * DashScopeChatOptions represents the options for the DashScopeChat model.
 *
 * @author Sober
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DashScopeChatOptions implements FunctionCallingOptions, ChatOptions {

	/**
	 * The ID of the model to be used.
	 */
	private @JsonProperty("model") String model;

	/**
	 * Parameters used to control the generation by the model.
	 */
	@NestedConfigurationProperty
	private @JsonProperty("parameters") Parameters parameters = new Parameters();
	/**
	 * OpenAI Tool Function Callbacks to register with the ChatModel.
	 * For Prompt Options the functionCallbacks are automatically enabled for the duration of the prompt execution.
	 * For Default Options the functionCallbacks are registered but disabled by default. Use the enableFunctions to set the functions
	 * from the registry to be used by the ChatModel chat completion requests.
	 */
	@NestedConfigurationProperty
	@JsonIgnore
	private List<FunctionCallback> functionCallbacks = new ArrayList<>();

	/**
	 * List of functions, identified by their names, to configure for function calling in
	 * the chat completion requests.
	 * Functions with those names must exist in the functionCallbacks registry.
	 * The {@link #functionCallbacks} from the PromptOptions are automatically enabled for the duration of the prompt execution.
	 *
	 * Note that function enabled with the default options are enabled for all chat completion requests. This could impact the token count and the billing.
	 * If the functions is set in a prompt options, then the enabled functions are only active for the duration of this prompt execution.
	 */
	@NestedConfigurationProperty
	@JsonIgnore
	private Set<String> functions = new HashSet<>();

	/**
	 * Parameters for controlling model generation
	 */
	private static class Parameters {

		/**
		 * Determines the format of the result returned by the model. It can be set to 'text' or 'message'. Default is 'text'.
		 */
		private @JsonProperty("result_format") String resultFormat;

		/**
		 * An unsigned 64-bit integer used as a random seed to control the randomness of the model's output.
		 * When using a specific seed, the model attempts to generate similar results, but they might not be exactly identical due to the nature of the model.
		 */
		private @JsonProperty("seed") Integer seed;

		/**
		 *  Sets the maximum number of tokens that the model will generate.
		 */
		private @JsonProperty("max_tokens") String maxTokens;

		/**
		 * A probability threshold used in nucleus sampling.
		 * Tokens with cumulative probabilities above this threshold are selected for sampling.
		 * Higher values increase randomness while lower values decrease it. Should not exceed 1.0.
		 */
		private @JsonProperty("top_p") Float topP;

		/**
		 * Specifies the size of the candidate set from which tokens are sampled.
		 * Larger values increase randomness while smaller values increase determinism.
		 */
		private @JsonProperty("top_k") Integer topK;

		/**
		 * Controls the repetition of sequences within the generated text. Higher values reduce repetition.
		 * There's no strict range, but 1.0 means no penalty is applied.
		 */
		private @JsonProperty("repetition_penalty") Float repetitionPenalty;

		/**
		 * Affects the overall sequence repetition. Higher values reduce repetition, and it ranges from -2.0 to 2.0.
		 */
		private @JsonProperty("presence_penalty") Float presencePenalty;

		/**
		 * Controls the degree of randomness and diversity. Specifically, the temperature value
		 * regulates the extent of smoothing applied to the probability distribution of each candidate word
		 * during text generation. A higher temperature value reduces the peaks of the probability distribution,
		 * allowing for more low-probability words to be selected, resulting in more diverse outcomes;
		 * whereas a lower temperature value enhances the peaks of the probability distribution, making high-probability
		 * words more likely to be chosen, leading to more deterministic results.
		 * Value range: [0, 2), it is not recommended to set this value to 0 as it would be meaningless.
		 */
		private @JsonProperty("temperature") Float temperature;


		/**
		 * The stop parameter is used for precise control over the content generation process, automatically halting
		 * when the generated content is about to include a specified string or token_id, ensuring that the generated
		 * content does not contain the specified content.
		 * It is not allowed to input both token_id and strings as elements simultaneously, for example, you cannot
		 * specify stop as ["hello", 104307].
		 */
		@NestedConfigurationProperty
		private @JsonProperty("stop") List<String> stop;

		/**
		 * The model comes with built-in internet search services. This parameter controls whether the model should
		 * refer to internet search results while generating text.
		 */
		private @JsonProperty("enable_search") Boolean enableSearch;

		/**
		 * Controls whether to enable incremental output in streaming output mode, i.e., whether subsequent output
		 * includes previously output content.
		 * Setting it to True will enable incremental output mode, where subsequent outputs do not include already
		 * output content, requiring you to concatenate the overall output yourself; setting it to False will include
		 * previously output content.
		 */
		private @JsonProperty("incremental_output") Boolean incrementalOutput;

		/**
		 * Specifies a list of tools that the model can call upon. When multiple tools are input, the model selects
		 * one to generate results.
		 */
		@NestedConfigurationProperty
		private @JsonProperty("tools") List<DashScopeApi.FunctionTool> tools;

		/**
		 * When using the tools parameter, this controls the model's invocation of a specified tool. Possible values are:
		 * 'none' indicates no tool should be called. When the tools parameter is empty, the default value is 'none'.
		 * 'auto' allows the model to decide whether to call a tool, which may result in calling or not calling a tool.
		 * When the tools parameter is not empty, the default value is 'auto'.
		 * An object structure can specify the model to call a specific tool. For example, {"type": "function",
		 * "function": {"name": "user_function"}}
		 * Currently, 'type' only supports 'function'
		 * 'function.name' represents the name of the tool expected to be called
		 *
		 */
		private @JsonProperty("tool_choice") String toolChoice;

		public String getResultFormat() {
			return resultFormat;
		}

		public void setResultFormat(String resultFormat) {
			this.resultFormat = resultFormat;
		}

		public Integer getSeed() {
			return seed;
		}

		public void setSeed(Integer seed) {
			this.seed = seed;
		}

		public String getMaxTokens() {
			return maxTokens;
		}

		public void setMaxTokens(String maxTokens) {
			this.maxTokens = maxTokens;
		}

		public Float getTopP() {
			return topP;
		}

		public void setTopP(Float topP) {
			this.topP = topP;
		}

		public Integer getTopK() {
			return topK;
		}

		public void setTopK(Integer topK) {
			this.topK = topK;
		}

		public Float getRepetitionPenalty() {
			return repetitionPenalty;
		}

		public void setRepetitionPenalty(Float repetitionPenalty) {
			this.repetitionPenalty = repetitionPenalty;
		}

		public Float getPresencePenalty() {
			return presencePenalty;
		}

		public void setPresencePenalty(Float presencePenalty) {
			this.presencePenalty = presencePenalty;
		}

		public Float getTemperature() {
			return temperature;
		}

		public void setTemperature(Float temperature) {
			this.temperature = temperature;
		}

		public List<String> getStop() {
			return stop;
		}

		public void setStop(List<String> stop) {
			this.stop = stop;
		}

		public Boolean getEnableSearch() {
			return enableSearch;
		}

		public void setEnableSearch(Boolean enableSearch) {
			this.enableSearch = enableSearch;
		}

		public Boolean getIncrementalOutput() {
			return incrementalOutput;
		}

		public void setIncrementalOutput(Boolean incrementalOutput) {
			this.incrementalOutput = incrementalOutput;
		}

		public List<DashScopeApi.FunctionTool> getTools() {
			return tools;
		}

		public void setTools(List<DashScopeApi.FunctionTool> tools) {
			this.tools = tools;
		}

		public String getToolChoice() {
			return toolChoice;
		}

		public void setToolChoice(String toolChoice) {
			this.toolChoice = toolChoice;
		}

	}

	public static Builder builder() {
		return new Builder();
	}



	public static class Builder {

		private final DashScopeChatOptions options;

		public Builder() {
			this.options = new DashScopeChatOptions();
		}

		public Builder(DashScopeChatOptions options) {
			this.options = options;
		}

		public Builder withModel(DashScopeApi.ChatModel model) {
			this.options.model = model.getModelName();
			return this;
		}

		public Builder withResultFormat(String resultFormat) {
			this.options.setResultFormat(resultFormat);
			return this;
		}

		public Builder withSeed(Integer seed) {
			this.options.setSeed(seed);
			return this;
		}

		public Builder withMaxTokens(String maxTokens) {
			this.options.setMaxTokens(maxTokens);
			return this;
		}

		public Builder withTopP(Float topP) {
			this.options.setTopP(topP);
			return this;
		}

		public Builder withTopK(Integer topK) {
			this.options.setTopK(topK);
			return this;
		}

		public Builder withRepetitionPenalty(Float repetitionPenalty) {
			this.options.setRepetitionPenalty(repetitionPenalty);
			return this;
		}

		public Builder withPresencePenalty(Float presencePenalty) {
			this.options.setPresencePenalty(presencePenalty);
			return this;
		}

		public Builder withTemperature(Float temperature) {
			this.options.setTemperature(temperature);
			return this;
		}

		public Builder withStop(List<String> stop) {
			this.options.setStop(stop);
			return this;
		}

		public Builder withEnableSearch(Boolean enableSearch) {
			this.options.setEnableSearch(enableSearch);
			return this;
		}

		public Builder withIncrementalOutput(Boolean incrementalOutput) {
			this.options.setIncrementalOutput(incrementalOutput);
			return this;
		}

		public Builder withTools(List<DashScopeApi.FunctionTool> tools) {
			this.options.setTools(tools);
			return this;
		}

		public Builder withToolChoice(String toolChoice) {
			this.options.setToolChoice(toolChoice);
			return this;
		}

		public Builder withFunctionCallbacks(List<FunctionCallback> functionCallbacks) {
			this.options.functionCallbacks = functionCallbacks;
			return this;
		}

		public Builder withFunctions(Set<String> functionNames) {
			Assert.notNull(functionNames, "Function names must not be null");
			this.options.functions = functionNames;
			return this;
		}

		public Builder withFunction(String functionName) {
			Assert.hasText(functionName, "Function name must not be empty");
			this.options.functions.add(functionName);
			return this;
		}


		public DashScopeChatOptions build() {
			return this.options;
		}

	}



	public String getResultFormat() {
		return this.parameters.resultFormat;
	}

	public void setResultFormat(String resultFormat) {
		this.parameters.resultFormat = resultFormat;
	}

	public Integer getSeed() {
		return this.parameters.seed;
	}

	public void setSeed(Integer seed) {
		this.parameters.seed = seed;
	}

	public String getMaxTokens() {
		return this.parameters.maxTokens;
	}

	public void setMaxTokens(String maxTokens) {
		this.parameters.maxTokens = maxTokens;
	}

	public void setTopP(Float topP) {
		this.parameters.topP = topP;
	}

	public void setTopK(Integer topK) {
		this.parameters.topK = topK;
	}

	public Float getRepetitionPenalty() {
		return this.parameters.repetitionPenalty;
	}

	public void setRepetitionPenalty(Float repetitionPenalty) {
		this.parameters.repetitionPenalty = repetitionPenalty;
	}

	public Float getPresencePenalty() {
		return this.parameters.presencePenalty;
	}

	public void setPresencePenalty(Float presencePenalty) {
		this.parameters.presencePenalty = presencePenalty;
	}

	public void setTemperature(Float temperature) {
		this.parameters.temperature = temperature;
	}

	public List<String> getStop() {
		return this.parameters.stop;
	}

	public void setStop(List<String> stop) {
		this.parameters.stop = stop;
	}

	public Boolean getEnableSearch() {
		return this.parameters.enableSearch;
	}

	public void setEnableSearch(Boolean enableSearch) {
		this.parameters.enableSearch = enableSearch;
	}

	public Boolean getIncrementalOutput() {
		return this.parameters.incrementalOutput;
	}

	public void setIncrementalOutput(Boolean incrementalOutput) {
		this.parameters.incrementalOutput = incrementalOutput;
	}

	public List<DashScopeApi.FunctionTool> getTools() {
		return this.parameters.tools;
	}

	public void setTools(List<DashScopeApi.FunctionTool> tools) {
		this.parameters.tools = tools;
	}

	public String getToolChoice() {
		return this.parameters.toolChoice;
	}

	public void setToolChoice(String toolChoice) {
		this.parameters.toolChoice = toolChoice;
	}

	@Override
	public Float getTemperature() {
		return this.parameters.getTemperature();
	}

	@Override
	public Float getTopP() {
		return this.parameters.getTopP();
	}

	@Override
	public Integer getTopK() {
		return this.parameters.getTopK();
	}

	@Override
	public List<FunctionCallback> getFunctionCallbacks() {
		return this.functionCallbacks;
	}

	@Override
	public void setFunctionCallbacks(List<FunctionCallback> functionCallbacks) {
		this.functionCallbacks = functionCallbacks;
	}

	@Override
	public Set<String> getFunctions() {
		return this.functions;
	}

	@Override
	public void setFunctions(Set<String> functions) {
		this.functions = functions;
	}
}
