package xyz.bluspring.gitsyncher;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class GitSyncher implements ModInitializer {
    public static final AtomicReference<CommandSourceStack> handler = new AtomicReference<>(null);
    private static final ConcurrentLinkedQueue<String> commands = new ConcurrentLinkedQueue<>();
    private static final ExecutorService service = Executors.newFixedThreadPool(2);
    public static Path workingDir = FabricLoader.getInstance().getGameDir();

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("git")
                .requires(ctx -> ctx.hasPermission(2))
                .then(Commands.literal("pull")
                    .executes(ctx -> {
                        handler.set(ctx.getSource());
                        workingDir = FabricLoader.getInstance().getGameDir().resolve("world/datapacks");
                        runCommand("git pull");

                        return 1;
                    })
                )
                .then(Commands.literal("run")
                    .then(
                        Commands.argument("command", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                handler.set(ctx.getSource());
                                queueCommand(StringArgumentType.getString(ctx, "command"));

                                return 1;
                            })
                    )
                )
                .then(Commands.literal("cd")
                    .then(
                        Commands.argument("dir", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                var newDir = workingDir.resolve(StringArgumentType.getString(ctx, "dir")).toAbsolutePath();

                                if (!newDir.toFile().exists() || !newDir.toFile().isDirectory()) {
                                    ctx.getSource().sendFailure(Component.literal("Directory does not exist!"));
                                    return 0;
                                }

                                workingDir = newDir;

                                ctx.getSource().sendSystemMessage(Component.literal("Changed directory to " + workingDir));

                                return 1;
                            })
                    )
                )
            );
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler1, server) -> {
            if (handler.get() != null && handler.get().isPlayer() && handler.get().getPlayer().getUUID().equals(handler1.player.getUUID())) {
                handler.set(null);
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            service.shutdown();
            try {
                service.awaitTermination(5_000L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public static void queueCommand(String command) {
        if (commands.isEmpty())
            submitCommand(command);
        else
            commands.add(command);
    }

    public static void submitCommand(String command) {
        service.submit(() -> {
            runCommand(command);
        });
    }

    public static void runCommand(String command) {
        var runtime = Runtime.getRuntime();

        try {
            var proc = runtime.exec(command, null, workingDir.toFile());
            var input = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            var error = new BufferedReader(new InputStreamReader(proc.getErrorStream()));

            String s = null;
            while ((s = input.readLine()) != null || (s = error.readLine()) != null) {
                var handler = GitSyncher.handler.get();

                if (handler != null) {
                    handler.sendSystemMessage(Component.literal(s));
                }

                System.out.println(s);
            }
        } catch (IOException e) {
            var handler = GitSyncher.handler.get();

            if (handler != null) {
                handler.sendFailure(Component.literal(e.getLocalizedMessage()));
            }

            e.printStackTrace();
        }

        if (commands.isEmpty())
            handler.set(null);
        else
            runCommand(commands.remove());
    }
}
