package com.nectariich.create_flippies.compat;

import java.util.function.Supplier;

import net.minecraftforge.api.distmarker.Dist;
//? if forge {
import net.minecraftforge.fml.DistExecutor;
//?} else {
/*import net.neoforged.fml.loading.FMLEnvironment;
*///?}

/**
 * Runs client-only code from common code, the way {@code DistExecutor.unsafeRunWhenOn}
 * did on 1.20.1. NeoForge dropped {@code DistExecutor}.
 *
 * The double supplier is what keeps a dedicated server safe: the client class is only
 * named inside the inner lambda, so it is resolved only when the outer one is called,
 * which happens on the client alone.
 */
public final class ClientOnly {

    private ClientOnly() {}

    public static void run(Supplier<Runnable> toRun) {
        //? if forge {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, toRun);
        //?} else {
        /*if (FMLEnvironment.dist == Dist.CLIENT)
            toRun.get().run();
        *///?}
    }
}
