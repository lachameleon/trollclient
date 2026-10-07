package com.trollclient.mixin;

import net.minecraft.client.gui.components.toasts.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.BitSet;

/** Lets the HUD array list duck under vanilla toasts instead of drawing over them. */
@Mixin(ToastManager.class)
public interface ToastManagerAccessor {
	@Accessor("occupiedSlots")
	BitSet troll$getOccupiedSlots();
}
