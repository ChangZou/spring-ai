package org.springframework.ai.dashscope.metadata;

import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.dashscope.api.DashScopeApi;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

import java.util.HashMap;

/**
 * {@link ChatResponseMetadata} implementation for {@literal DashScope}.
 *
 * @author Sober
 * @see ChatResponseMetadata
 * @see RateLimit
 * @see Usage
 */
public class DashScopeChatResponseMetadata extends HashMap<String, Object> implements ChatResponseMetadata {

	protected static final String AI_METADATA_STRING = "{ @type: %1$s, id: %2$s, model: %3$s, usage: %4$s, rateLimit: %5$s }";

	public static DashScopeChatResponseMetadata from(DashScopeApi.ChatCompletion result) {
		Assert.notNull(result, "DashScop ChatCompletionResult must not be null");
		return new DashScopeChatResponseMetadata(result.requestId(), null, result.usage());
	}

	private final String id;

	@Nullable
	private String model;

	@Nullable
	private RateLimit rateLimit;

	private final Usage usage;

	protected DashScopeChatResponseMetadata(String id, @Nullable String model, DashScopeApi.Usage usage) {
		this(id, model, DashScopeUsage.from(usage), null);
	}

	protected DashScopeChatResponseMetadata(String id, @Nullable String model, Usage usage,
											@Nullable RateLimit rateLimit) {
		this.id = id;
		this.model = model;
		this.usage = usage;
		this.rateLimit = rateLimit;
	}

	@Override
	public String getId() {
		return this.id;
	}

	@Override
	public String getModel() {
		return this.model;
	}

	@Override
	@Nullable
	public RateLimit getRateLimit() {
		RateLimit rateLimit = this.rateLimit;
		return rateLimit != null ? rateLimit : new EmptyRateLimit();
	}

	@Override
	public Usage getUsage() {
		Usage usage = this.usage;
		return usage != null ? usage : new EmptyUsage();
	}

	public DashScopeChatResponseMetadata withRateLimit(RateLimit rateLimit) {
		this.rateLimit = rateLimit;
		return this;
	}

	public DashScopeChatResponseMetadata withModel(String model) {
		this.model = model;
		return this;
	}

	@Override
	public String toString() {
		return AI_METADATA_STRING.formatted(getClass().getName(), getId(), getModel(), getUsage(), getRateLimit());
	}

}
