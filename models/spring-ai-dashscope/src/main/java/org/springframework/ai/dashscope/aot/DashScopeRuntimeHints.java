package org.springframework.ai.dashscope.aot;

import org.springframework.ai.dashscope.api.DashScopeApi;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import static org.springframework.ai.aot.AiRuntimeHints.findJsonAnnotatedClassesInPackage;

/**
 * The DashScopeRuntimeHints class is responsible for registering runtime hints for DashScope
 * API classes.
 *
 * @author Sober
 */
public class DashScopeRuntimeHints implements RuntimeHintsRegistrar {

	@Override
	public void registerHints(@NonNull RuntimeHints hints, @Nullable ClassLoader classLoader) {
		var mcs = MemberCategory.values();
		for (var tr : findJsonAnnotatedClassesInPackage(DashScopeApi.class))
			hints.reflection().registerType(tr, mcs);
	}
}
