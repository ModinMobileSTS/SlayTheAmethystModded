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
}
