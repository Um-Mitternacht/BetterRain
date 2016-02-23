/*
 * This file is part of Dynamic Surroundings, licensed under the MIT License (MIT).
 *
 * Copyright (c) OreCruncher
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.blockartistry.mod.DynSurround.client.sound;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.blockartistry.mod.DynSurround.ModLog;
import org.blockartistry.mod.DynSurround.ModOptions;
import org.blockartistry.mod.DynSurround.client.EnvironStateHandler.EnvironState;
import org.blockartistry.mod.DynSurround.compat.BlockPos;
import org.lwjgl.BufferUtils;
import org.lwjgl.LWJGLException;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALC11;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;
import paulscode.sound.SoundSystemConfig;

@SideOnly(Side.CLIENT)
public class SoundManager {

	private static final int AGE_THRESHOLD_TICKS = 5;
	private static final int SOUND_QUEUE_SLACK = 6;
	private static final Map<SoundEffect, Emitter> emitters = new HashMap<SoundEffect, Emitter>();

	private static final List<SpotSound> pending = new ArrayList<SpotSound>();

	public static void clearSounds() {
		for (final Emitter emit : emitters.values())
			emit.fade();
		emitters.clear();
		pending.clear();
	}

	public static void queueAmbientSounds(final List<SoundEffect> sounds) {
		// Need to remove sounds that are active but not
		// in the incoming list
		final List<SoundEffect> active = new ArrayList<SoundEffect>(emitters.keySet());
		for (final SoundEffect effect : active) {
			if (!sounds.contains(effect))
				emitters.remove(effect).fade();
			else {
				final Emitter emitter = emitters.get(effect);
				SoundEffect incoming = null;
				for(final SoundEffect sound: sounds)
					if(sound.equals(effect)) {
						incoming = sound;
						break;
					}
				emitter.setVolume(incoming.getVolume());
				sounds.remove(effect);
			}
		}

		// Add sounds from the incoming list that are not
		// active.
		for (final SoundEffect sound : sounds)
			emitters.put(sound, new Emitter(sound));
	}

	public static void update() {
		final Iterator<Entry<SoundEffect, Emitter>> itr = emitters.entrySet().iterator();
		while (itr.hasNext()) {
			final Entry<SoundEffect, Emitter> e = itr.next();
			e.getValue().update();
		}

		final Iterator<SpotSound> pitr = pending.iterator();
		while (pitr.hasNext()) {
			final SpotSound sound = pitr.next();
			if (sound.getTickAge() >= AGE_THRESHOLD_TICKS)
				pitr.remove();
			else if (sound.getTickAge() >= 0 && canFitSound()) {
				Minecraft.getMinecraft().getSoundHandler().playSound(sound);
				pitr.remove();
			}
		}
	}

	public static int currentSoundCount() {
		return Minecraft.getMinecraft().getSoundHandler().sndManager.playingSounds.size();
	}

	public static int maxSoundCount() {
		return SoundSystemConfig.getNumberNormalChannels() + SoundSystemConfig.getNumberStreamingChannels();
	}

	private static boolean canFitSound() {
		return currentSoundCount() < (SoundSystemConfig.getNumberNormalChannels() - SOUND_QUEUE_SLACK);
	}

	public static void playSoundAtPlayer(EntityPlayer player, final SoundEffect sound) {

		if (player == null)
			player = EnvironState.getPlayer();

		final SoundHandler handler = Minecraft.getMinecraft().getSoundHandler();
		final SpotSound s = new SpotSound(player, sound);

		if (!canFitSound())
			pending.add(s);
		else
			handler.playSound(s);
	}

	public static void playSoundAt(final BlockPos pos, final SoundEffect sound,
			final int tickDelay) {
		if (tickDelay > 0 && !canFitSound())
			return;

		final SoundHandler handler = Minecraft.getMinecraft().getSoundHandler();
		final SpotSound s = new SpotSound(pos, sound, tickDelay);

		if (tickDelay > 0 || !canFitSound())
			pending.add(s);
		else
			handler.playSound(s);
	}

	public static List<String> getSounds() {
		final List<String> result = new ArrayList<String>();
		for (final SoundEffect effect : emitters.keySet())
			result.add("EMITTER: " + effect.toString() + "[vol:" + emitters.get(effect).getVolume() + "]");
		for (final SpotSound effect : pending)
			result.add((effect.getTickAge() < 0 ? "DELAYED: " : "PENDING: ") + effect.getSoundEffect().toString());
		return result;
	}

	public static void configureSound() {
		int totalChannels = -1;

		try {
			AL.create();
			final IntBuffer ib = BufferUtils.createIntBuffer(1);
			ALC10.alcGetInteger(AL.getDevice(), ALC11.ALC_MONO_SOURCES, ib);
			totalChannels = ib.get(0);
			AL.destroy();
		} catch (LWJGLException e) {
			e.printStackTrace();
		}

		int normalChannels = ModOptions.normalSoundChannelCount;
		int streamChannels = ModOptions.streamingSoundChannelCount;

		if (ModOptions.autoConfigureChannels && totalChannels > 64) {
			final int maxCount = Math.max((totalChannels + 1) / 2, 32);
			normalChannels = MathHelper.floor_float(maxCount * 0.875F);
			streamChannels = maxCount - normalChannels;
		}

		ModLog.info("Sound channels: %d normal, %d streaming (total avail: %s)", normalChannels, streamChannels,
				totalChannels == -1 ? "UNKNOWN" : Integer.toString(totalChannels));
		SoundSystemConfig.setNumberNormalChannels(normalChannels);
		SoundSystemConfig.setNumberStreamingChannels(streamChannels);

	}
}
