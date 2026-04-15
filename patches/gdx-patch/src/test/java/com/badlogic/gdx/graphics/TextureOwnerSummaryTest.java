package com.badlogic.gdx.graphics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TextureOwnerSummaryTest {
	@Test
	public void classifyOwnerKey_prefersKnownDownfallNamespaces () {
		String stackKey =
			"downfall.events.HeartEvent#create:41 <- downfall.vfx.CustomAnimatedNPC#render:87";

		assertEquals(
			"downfall<-downfallresources",
			TextureOwnerSummary.classifyOwnerKey("downfallresources", null, stackKey)
		);
	}

	@Test
	public void classifyOwnerKey_usesLoaderPrefixesButKeepsTextureGroup () {
		String stackKey =
			"basemod.abstracts.CustomCard#loadCardImage:25 <- "
				+ "com.megacrit.cardcrawl.cards.AbstractCard#initializeInherentMods:13";

		assertEquals(
			"basemod<-frierenmod",
			TextureOwnerSummary.classifyOwnerKey("frierenmod", "images/cards/frieren/test.png", stackKey)
		);
	}

	@Test
	public void summarizeOwnerSample_compactsStackFrames () {
		String stackKey =
			"frierenmod.characters.FrierenCharacter#render:25 <- "
				+ "frierenmod.ui.FrierenSelectScreen#build:90 <- "
				+ "com.megacrit.cardcrawl.helpers.ImageMaster#loadImage:12";

		String sample = TextureOwnerSummary.summarizeOwnerSample(stackKey, "fallback");

		assertTrue(sample.contains("FrierenCharacter#render"));
		assertTrue(sample.contains("FrierenSelectScreen#build"));
	}

	@Test
	public void extractExternalNamespaceGroup_returnsTopLevelPackage () {
		String stackKey =
			"frierenmod.characters.FrierenCharacter#render:25 <- "
				+ "com.megacrit.cardcrawl.helpers.ImageMaster#loadImage:12";

		assertEquals("frierenmod", TextureOwnerSummary.extractExternalNamespaceGroup(stackKey));
	}
}
