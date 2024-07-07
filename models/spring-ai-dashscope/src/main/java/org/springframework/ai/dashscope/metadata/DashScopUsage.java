package org.springframework.ai.dashscope.metadata;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.dashscope.api.DashScopeApi;
import org.springframework.util.Assert;

public class DashScopUsage implements Usage {


	public static DashScopUsage from(DashScopeApi.Usage usage) {
		return new DashScopUsage(usage);
	}

	private final DashScopeApi.Usage usage;

	protected DashScopUsage(DashScopeApi.Usage usage) {
		Assert.notNull(usage, "DashScope Usage must not be null");
		this.usage = usage;
	}

	protected DashScopeApi.Usage getUsage() {
		return this.usage;
	}

	@Override
	public Long getPromptTokens() {
		return getUsage().inputTokens().longValue();
	}

	@Override
	public Long getGenerationTokens() {
		return getUsage().outputTokens().longValue();
	}

	@Override
	public Long getTotalTokens() {
		return getUsage().totalTokens().longValue();
	}

	@Override
	public String toString() {
		return getUsage().toString();
	}
}
