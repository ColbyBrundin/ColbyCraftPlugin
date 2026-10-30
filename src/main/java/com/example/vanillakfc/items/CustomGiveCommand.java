package com.example.vanillakfc.items;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * {@code /customgive <targets> <item> [amount]} - vanilla's /give for the
 * plugin's custom items, which vanilla's can't name: they're built on
 * vanilla items (a Warden Helmet is a netherite helmet underneath), and new
 * item ids only exist with a client mod. Items are named
 * {@code vanillakfc:<id>}, tab-completed; the bare id works too.
 *
 * <p>Operators only ({@value #PERMISSION}). Like /give, whatever doesn't fit
 * in a player's inventory is dropped at their feet.
 */
public final class CustomGiveCommand {

    public static final String PERMISSION = "vanillakfc.give";
    private static final int MAX_AMOUNT = 64 * 36;

    private CustomGiveCommand() {
    }

    public static LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("customgive")
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .then(Commands.argument("targets", ArgumentTypes.players())
                        .then(Commands.argument("item", ArgumentTypes.key())
                                .suggests(CustomGiveCommand::suggestItems)
                                .executes(context -> give(context, 1))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1, MAX_AMOUNT))
                                        .executes(context -> give(context, IntegerArgumentType.getInteger(context, "amount"))))))
                .build();
    }

    private static CompletableFuture<Suggestions> suggestItems(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        String typed = builder.getRemainingLowerCase();
        for (String id : CustomItems.ALL.keySet()) {
            String full = CustomItems.namespace() + ":" + id;
            if (full.startsWith(typed) || id.startsWith(typed)) builder.suggest(full);
        }
        return builder.buildFuture();
    }

    private static int give(CommandContext<CommandSourceStack> context, int amount) throws CommandSyntaxException {
        Key key = context.getArgument("item", Key.class);
        // A bare id parses as minecraft:<id>, so that's taken to mean ours too.
        boolean ours = key.namespace().equals(CustomItems.namespace()) || key.namespace().equals(Key.MINECRAFT_NAMESPACE);
        Supplier<ItemStack> factory = ours ? CustomItems.ALL.get(key.value()) : null;
        if (factory == null) {
            context.getSource().getSender().sendMessage(Component.text(
                    "Unknown custom item: " + key.asString() + ". Known: "
                            + String.join(", ", CustomItems.ALL.keySet().stream()
                                    .map(id -> CustomItems.namespace() + ":" + id).sorted().toList()),
                    NamedTextColor.RED));
            return 0;
        }

        List<Player> targets = context.getArgument("targets", PlayerSelectorArgumentResolver.class)
                .resolve(context.getSource());
        ItemStack sample = factory.get();
        for (Player target : targets) {
            ItemStack stack = factory.get();
            stack.setAmount(amount);
            // addItem splits past the stack size itself; what's left is dropped.
            Map<Integer, ItemStack> leftover = target.getInventory().addItem(stack);
            for (ItemStack rest : leftover.values()) {
                target.getWorld().dropItem(target.getLocation(), rest, item -> item.setOwner(target.getUniqueId()));
            }
        }

        Component what = Component.text(amount + " ").append(sample.displayName());
        Component who = targets.size() == 1
                ? Component.text(targets.getFirst().getName())
                : Component.text(targets.size() + " players");
        context.getSource().getSender().sendMessage(Component.text("Gave ").append(what).append(Component.text(" to ")).append(who));
        return targets.size();
    }
}
