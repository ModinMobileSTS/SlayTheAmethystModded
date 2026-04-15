package com.badlogic.gdx.graphics.glutils;

final class FrameBufferOwnerSummary {
	private static final String SCALED_RENDER_PIPELINE_FRAGMENT =
		"com.badlogic.gdx.backends.lwjgl.LwjglApplication$ScaledRenderPipeline";
	private static final String APPLY_SCREEN_POST_PROCESSOR_FRAGMENT =
		"basemod.patches.com.megacrit.cardcrawl.core.CardCrawlGame.ApplyScreenPostProcessor";
	private static final String[] EFFECT_STACK_FRAGMENTS = {
		".vfx.",
		".effect.",
		".effects.",
		".postfx",
		".postprocess",
		".shader",
		".mask",
		".glow",
		".blur",
		".outline",
		".particles.",
		".cutscene.",
		".cutscenes."
	};

	private FrameBufferOwnerSummary () {
	}

	static String resolveManagerProtectReason (String stackKey) {
		if (containsStackFragment(stackKey, SCALED_RENDER_PIPELINE_FRAGMENT)) {
			return "scaled_render_pipeline";
		}
		if (containsStackFragment(stackKey, APPLY_SCREEN_POST_PROCESSOR_FRAGMENT)) {
			return "ApplyScreenPostProcessor";
		}
		return null;
	}

	static String resolvePressureDownscaleProtectReason (String stackKey) {
		return resolveManagerProtectReason(stackKey);
	}

	static boolean isExternalModStack (String stackKey) {
		if (stackKey == null || stackKey.length() == 0) return false;
		String[] frames = stackKey.split(" <- ");
		for (int i = 0; i < frames.length; i++) {
			String className = extractFrameClassName(frames[i]);
			String ownerKey = classifyExternalOwnerKey(className);
			if (ownerKey == null) continue;
			if (ownerKey.startsWith("basemod<-") || ownerKey.startsWith("modthespire<-")) continue;
			return true;
		}
		return false;
	}

	static boolean isEffectLikeStack (String stackKey) {
		return containsAnyStackFragment(stackKey, EFFECT_STACK_FRAGMENTS);
	}

	static String classifyOwnerKey (String stackKey) {
		if (stackKey == null || stackKey.length() == 0) return "core<-unknown";
		String[] frames = stackKey.split(" <- ");
		for (int i = 0; i < frames.length; i++) {
			String className = extractFrameClassName(frames[i]);
			String ownerKey = classifyExternalOwnerKey(className);
			if (ownerKey != null) {
				return ownerKey;
			}
		}
		for (int i = 0; i < frames.length; i++) {
			String className = extractFrameClassName(frames[i]);
			String ownerKey = classifyCoreOwnerKey(className);
			if (ownerKey != null) {
				return ownerKey;
			}
		}
		return "core<-unknown";
	}

	static String summarizeOwnerSample (String stackKey) {
		if (stackKey == null || stackKey.length() == 0) return "";
		String[] frames = stackKey.split(" <- ");
		StringBuilder builder = new StringBuilder(96);
		int appended = 0;
		for (int i = 0; i < frames.length; i++) {
			String className = extractFrameClassName(frames[i]);
			String methodName = extractFrameMethodName(frames[i]);
			if (className == null || methodName == null) continue;
			if (appended > 0) {
				builder.append(" <- ");
			}
			builder.append(simpleClassName(className)).append("#").append(methodName);
			appended++;
			if (appended >= 3) {
				break;
			}
		}
		return appended == 0 ? "" : builder.toString();
	}

	private static String classifyExternalOwnerKey (String className) {
		if (className == null || className.length() == 0) return null;
		if (className.startsWith("com.megacrit.cardcrawl.")) return null;
		if (className.startsWith("com.badlogic.gdx.")) return null;
		if (className.startsWith("java.")) return null;
		if (className.startsWith("javax.")) return null;
		if (className.startsWith("sun.")) return null;
		if (className.startsWith("jdk.")) return null;
		if (className.startsWith("kotlin.")) return null;
		if (className.startsWith("org.lwjgl.")) return null;
		if (className.startsWith("org.apache.")) return null;
		if (className.startsWith("de.robojumper.")) return null;
		if (className.startsWith("com.esotericsoftware.")) return null;
		if (className.startsWith("io.stamethyst.")) return null;
		if (className.startsWith("basemod.")) {
			return "basemod<-" + simpleClassName(className);
		}
		if (className.startsWith("com.evacipated.cardcrawl.modthespire.")) {
			return "modthespire<-" + simpleClassName(className);
		}
		if (className.startsWith("com.evacipated.cardcrawl.mod.stslib.")
			|| className.startsWith("com.evacipated.cardcrawl.mod.stslib2.")) {
			return "stslib<-" + simpleClassName(className);
		}
		if (className.startsWith("downfall.")
			|| className.startsWith("charbosses.")
			|| className.startsWith("evilmod.")) {
			return "downfall<-" + simpleClassName(className);
		}
		return "external<-" + simpleClassName(className);
	}

	private static String classifyCoreOwnerKey (String className) {
		if (className == null || className.length() == 0) return null;
		if (className.indexOf("com.badlogic.gdx.backends.lwjgl.LwjglApplication$ScaledRenderPipeline") >= 0) {
			return "core<-scaled_render_pipeline";
		}
		if (!className.startsWith("com.megacrit.cardcrawl.")) return null;
		if (className.indexOf(".screens.mainMenu.") >= 0 || className.indexOf(".screens.charSelect.") >= 0) {
			return "core<-menu";
		}
		if (className.indexOf(".vfx.") >= 0
			|| className.indexOf(".effects.") >= 0
			|| className.indexOf(".cutscene.") >= 0
			|| className.indexOf(".cutscenes.") >= 0) {
			return "core<-effects";
		}
		if (className.indexOf(".map.") >= 0) {
			return "core<-map";
		}
		return "core<-" + simpleClassName(className);
	}

	private static boolean containsStackFragment (String stackKey, String fragment) {
		return stackKey != null && fragment != null && stackKey.indexOf(fragment) >= 0;
	}

	private static boolean containsAnyStackFragment (String stackKey, String[] fragments) {
		if (stackKey == null || stackKey.length() == 0 || fragments == null) return false;
		for (int i = 0; i < fragments.length; i++) {
			if (containsStackFragment(stackKey, fragments[i])) {
				return true;
			}
		}
		return false;
	}

	private static String extractFrameClassName (String frame) {
		if (frame == null) return null;
		int hashIndex = frame.indexOf('#');
		if (hashIndex <= 0) return null;
		return frame.substring(0, hashIndex);
	}

	private static String extractFrameMethodName (String frame) {
		if (frame == null) return null;
		int hashIndex = frame.indexOf('#');
		if (hashIndex < 0) return null;
		int colonIndex = frame.indexOf(':', hashIndex + 1);
		if (colonIndex > hashIndex) {
			return frame.substring(hashIndex + 1, colonIndex);
		}
		return hashIndex + 1 < frame.length() ? frame.substring(hashIndex + 1) : null;
	}

	private static String simpleClassName (String className) {
		if (className == null || className.length() == 0) return "unknown";
		int dollarIndex = className.lastIndexOf('$');
		int dotIndex = className.lastIndexOf('.');
		int startIndex = Math.max(dollarIndex, dotIndex);
		if (startIndex < 0 || startIndex + 1 >= className.length()) {
			return className;
		}
		return className.substring(startIndex + 1);
	}
}
