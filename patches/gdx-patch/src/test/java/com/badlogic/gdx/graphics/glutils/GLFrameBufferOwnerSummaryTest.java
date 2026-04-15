package com.badlogic.gdx.graphics.glutils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GLFrameBufferOwnerSummaryTest {
	@Test
	public void classifyOwnerKeyForStack_prefersKnownDownfallNamespaces () {
		String stackKey =
			"downfall.events.HeartEvent#create:41 <- downfall.vfx.CustomAnimatedNPC#render:87";

		assertEquals("downfall<-HeartEvent", FrameBufferOwnerSummary.classifyOwnerKey(stackKey));
		assertTrue(FrameBufferOwnerSummary.summarizeOwnerSample(stackKey).contains("HeartEvent#create"));
		assertTrue(FrameBufferOwnerSummary.summarizeOwnerSample(stackKey).contains("CustomAnimatedNPC#render"));
	}

	@Test
	public void classifyOwnerKeyForStack_mapsKnownLoaderNamespaces () {
		String stackKey =
			"basemod.patches.com.megacrit.cardcrawl.core.CardCrawlGame.ApplyScreenPostProcessor#Insert:12";

		assertEquals("basemod<-ApplyScreenPostProcessor", FrameBufferOwnerSummary.classifyOwnerKey(stackKey));
	}

	@Test
	public void resolveManagerProtectReason_identifiesProtectedPipelines () {
		assertEquals(
			"scaled_render_pipeline",
			FrameBufferOwnerSummary.resolveManagerProtectReason(
				"com.badlogic.gdx.backends.lwjgl.LwjglApplication$ScaledRenderPipeline#beginFrame:1"
			)
		);
		assertEquals(
			"ApplyScreenPostProcessor",
			FrameBufferOwnerSummary.resolvePressureDownscaleProtectReason(
				"basemod.patches.com.megacrit.cardcrawl.core.CardCrawlGame.ApplyScreenPostProcessor#Insert:12"
			)
		);
	}

	@Test
	public void classifyOwnerKeyForStack_fallsBackToCoreMenuOwner () {
		String stackKey =
			"com.megacrit.cardcrawl.screens.mainMenu.MainMenuScreen#render:123 <- "
				+ "com.megacrit.cardcrawl.screens.charSelect.CharacterSelectScreen#render:45";

		assertEquals("core<-menu", FrameBufferOwnerSummary.classifyOwnerKey(stackKey));
	}

	@Test
	public void classifyOwnerKeyForStack_usesExternalFallbackForUnknownMods () {
		String stackKey = "mod.awesome.FancyBuffer#build:7 <- mod.awesome.FancyScene#render:9";

		assertEquals("external<-FancyBuffer", FrameBufferOwnerSummary.classifyOwnerKey(stackKey));
	}

	@Test
	public void isExternalModStack_skipsBasemodButIncludesThirdPartyStacks () {
		assertTrue(FrameBufferOwnerSummary.isExternalModStack(
			"mod.awesome.FancyBuffer#build:7 <- mod.awesome.FancyScene#render:9"
		));
		assertTrue(FrameBufferOwnerSummary.isExternalModStack(
			"downfall.vfx.CustomAnimatedNPC#render:87"
		));
		assertTrue(!FrameBufferOwnerSummary.isExternalModStack(
			"basemod.patches.com.megacrit.cardcrawl.core.CardCrawlGame.ApplyScreenPostProcessor#Insert:12"
		));
	}

	@Test
	public void isEffectLikeStack_matchesSharedEffectFragments () {
		assertTrue(FrameBufferOwnerSummary.isEffectLikeStack(
			"com.megacrit.cardcrawl.vfx.SceneEffect#render:10"
		));
		assertTrue(FrameBufferOwnerSummary.isEffectLikeStack(
			"mod.awesome.postprocess.BloomPass#apply:7"
		));
		assertTrue(!FrameBufferOwnerSummary.isEffectLikeStack(
			"com.megacrit.cardcrawl.screens.mainMenu.MainMenuScreen#render:123"
		));
	}
}
