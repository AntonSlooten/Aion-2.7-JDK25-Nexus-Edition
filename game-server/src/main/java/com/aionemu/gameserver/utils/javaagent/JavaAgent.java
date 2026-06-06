package com.aionemu.gameserver.utils.javaagent;

import java.lang.instrument.Instrumentation;

import com.aionemu.commons.callbacks.enhancer.GlobalCallbackEnhancer;
import com.aionemu.commons.callbacks.enhancer.ObjectCallbackEnhancer;

public final class JavaAgent {

	private JavaAgent() {
	}

	public static void premain(String agentArgs, Instrumentation instrumentation) {
		instrumentation.addTransformer(new GlobalCallbackEnhancer());
		instrumentation.addTransformer(new ObjectCallbackEnhancer());
	}
}