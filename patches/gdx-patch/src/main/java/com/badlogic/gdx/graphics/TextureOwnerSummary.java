package com.badlogic.gdx.graphics;

final class TextureOwnerSummary {
	private TextureOwnerSummary () {
	}

	static String classifyOwnerKey (String groupKey, String sourcePath, String stackKey) {
		String safeGroupKey = groupKey == null || groupKey.length() == 0 ? "unknown" : groupKey;
		if (isDownfallAttribution(groupKey, sourcePath, stackKey)) {
			return "downfall<-" + safeGroupKey;
		}
		String ownerPrefix = classifyOwnerPrefix(stackKey);
		if (ownerPrefix == null || ownerPrefix.length() == 0) {
			return "core<-" + safeGroupKey;
		}
		return ownerPrefix + "<-" + safeGroupKey;
	}

	static String summarizeOwnerSample (String stackKey, String fallbackSample) {
		if (stackKey != null && stackKey.length() > 0) {
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
			if (appended > 0) {
				return builder.toString();
			}
		}
		return fallbackSample == null ? "" : fallbackSample;
	}

	static String extractExternalNamespaceGroup (String stackKey) {
		if (stackKey == null || stackKey.length() == 0) return null;
		String[] frames = stackKey.split(" <- ");
		for (int i = 0; i < frames.length; i++) {
			String className = extractFrameClassName(frames[i]);
			if (className == null || className.length() == 0) continue;
			if (!isExternalNamespaceClass(className)) continue;
			int packageSeparator = className.indexOf('.');
			if (packageSeparator > 0) {
				return className.substring(0, packageSeparator).toLowerCase();
			}
			return className.toLowerCase();
		}
		return null;
	}

	private static String classifyOwnerPrefix (String stackKey) {
		if (stackKey == null || stackKey.length() == 0) return null;
		String[] frames = stackKey.split(" <- ");
		for (int i = 0; i < frames.length; i++) {
			String className = extractFrameClassName(frames[i]);
			String prefix = classifyExternalOwnerPrefix(className);
			if (prefix != null) {
				return prefix;
			}
		}
		for (int i = 0; i < frames.length; i++) {
			String className = extractFrameClassName(frames[i]);
			String prefix = classifyCoreOwnerPrefix(className);
			if (prefix != null) {
				return prefix;
			}
		}
		return null;
	}

	private static String classifyExternalOwnerPrefix (String className) {
		if (className == null || className.length() == 0) return null;
		if (!isExternalNamespaceClass(className)) return null;
		if (className.startsWith("basemod.")) {
			return "basemod";
		}
		if (className.startsWith("com.evacipated.cardcrawl.modthespire.")) {
			return "modthespire";
		}
		if (className.startsWith("com.evacipated.cardcrawl.mod.stslib.")
			|| className.startsWith("com.evacipated.cardcrawl.mod.stslib2.")
			|| className.startsWith("stslib.")) {
			return "stslib";
		}
		if (className.startsWith("downfall.")
			|| className.startsWith("charbosses.")
			|| className.startsWith("evilmod.")) {
			return "downfall";
		}
		return "external";
	}

	private static String classifyCoreOwnerPrefix (String className) {
		if (className == null || className.length() == 0) return null;
		if (className.indexOf("com.badlogic.gdx.backends.lwjgl.LwjglApplication$ScaledRenderPipeline") >= 0) {
			return "core";
		}
		if (!className.startsWith("com.megacrit.cardcrawl.")) return null;
		return "core";
	}

	private static boolean isExternalNamespaceClass (String className) {
		if (className == null || className.length() == 0) return false;
		if (className.startsWith("com.megacrit.cardcrawl.")) return false;
		if (className.startsWith("com.badlogic.gdx.")) return false;
		if (className.startsWith("java.")) return false;
		if (className.startsWith("javax.")) return false;
		if (className.startsWith("sun.")) return false;
		if (className.startsWith("jdk.")) return false;
		if (className.startsWith("kotlin.")) return false;
		if (className.startsWith("org.lwjgl.")) return false;
		if (className.startsWith("org.apache.")) return false;
		if (className.startsWith("de.robojumper.")) return false;
		if (className.startsWith("com.esotericsoftware.")) return false;
		if (className.startsWith("io.stamethyst.")) return false;
		return true;
	}

	private static boolean isDownfallAttribution (String groupKey, String sourcePath, String stackKey) {
		if ("downfallresources".equals(groupKey)) {
			return true;
		}
		String normalizedSourcePath = normalizePath(sourcePath);
		if (containsPathFragment(normalizedSourcePath, "downfallresources/")) {
			return true;
		}
		return containsStackFragment(stackKey, "downfall.")
			|| containsStackFragment(stackKey, "charbosses.")
			|| containsStackFragment(stackKey, "evilmod.");
	}

	private static String normalizePath (String sourcePath) {
		if (sourcePath == null) return null;
		String normalized = sourcePath.replace('\\', '/').trim().toLowerCase();
		return normalized.length() == 0 ? null : normalized;
	}

	private static boolean containsStackFragment (String stackKey, String fragment) {
		return stackKey != null && fragment != null && stackKey.indexOf(fragment) >= 0;
	}

	private static boolean containsPathFragment (String sourcePath, String fragment) {
		return sourcePath != null && fragment != null && sourcePath.indexOf(fragment) >= 0;
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
