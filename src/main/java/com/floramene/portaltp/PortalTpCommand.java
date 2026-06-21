package com.floramene.portaltp;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.commands.arguments.coordinates.RotationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.commands.LookAt;
import net.minecraft.server.commands.LookAt.LookAtEntity;
import net.minecraft.server.commands.LookAt.LookAtPosition;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

public final class PortalTpCommand {

    private static final SimpleCommandExceptionType INVALID_POSITION =
            new SimpleCommandExceptionType(Component.translatable("commands.teleport.invalidPosition"));

    private static final SoundEvent PORTAL_TRAVEL_SOUND = BuiltInRegistries.SOUND_EVENT.getOptional(
            Identifier.withDefaultNamespace("block.portal.travel")
    ).orElse(SoundEvents.NOTE_BLOCK_PLING.value());

    private static final Holder<SoundEvent> PORTAL_TRAVEL_SOUND_HOLDER =
            BuiltInRegistries.SOUND_EVENT.wrapAsHolder(PORTAL_TRAVEL_SOUND);

    private PortalTpCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("portaltp")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))

                        // /portaltp <location>  (teleports the command's own executor)
                        .then(Commands.argument("location", Vec3Argument.vec3())
                                .executes(context -> teleportToPos(
                                        context.getSource(),
                                        Collections.singleton(context.getSource().getEntityOrException()),
                                        context.getSource().getLevel(),
                                        Vec3Argument.getCoordinates(context, "location"),
                                        null,
                                        null
                                )))

                        // /portaltp <destination>  (executor -> another entity)
                        .then(Commands.argument("destination", EntityArgument.entity())
                                .executes(context -> teleportToEntity(
                                        context.getSource(),
                                        Collections.singleton(context.getSource().getEntityOrException()),
                                        EntityArgument.getEntity(context, "destination")
                                )))

                        // /portaltp <targets> <location> [rotation] [facing ...]
                        // /portaltp <targets> <destination>
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.argument("location", Vec3Argument.vec3())
                                        .executes(context -> teleportToPos(
                                                context.getSource(),
                                                EntityArgument.getEntities(context, "targets"),
                                                context.getSource().getLevel(),
                                                Vec3Argument.getCoordinates(context, "location"),
                                                null,
                                                null
                                        ))
                                        .then(Commands.argument("rotation", RotationArgument.rotation())
                                                .executes(context -> teleportToPos(
                                                        context.getSource(),
                                                        EntityArgument.getEntities(context, "targets"),
                                                        context.getSource().getLevel(),
                                                        Vec3Argument.getCoordinates(context, "location"),
                                                        RotationArgument.getRotation(context, "rotation"),
                                                        null
                                                )))
                                        .then(Commands.literal("facing")
                                                .then(Commands.literal("entity")
                                                        .then(Commands.argument("facingEntity", EntityArgument.entity())
                                                                .executes(context -> teleportToPos(
                                                                        context.getSource(),
                                                                        EntityArgument.getEntities(context, "targets"),
                                                                        context.getSource().getLevel(),
                                                                        Vec3Argument.getCoordinates(context, "location"),
                                                                        null,
                                                                        new LookAtEntity(EntityArgument.getEntity(context, "facingEntity"), Anchor.FEET)
                                                                ))
                                                                .then(Commands.argument("facingAnchor", EntityAnchorArgument.anchor())
                                                                        .executes(context -> teleportToPos(
                                                                                context.getSource(),
                                                                                EntityArgument.getEntities(context, "targets"),
                                                                                context.getSource().getLevel(),
                                                                                Vec3Argument.getCoordinates(context, "location"),
                                                                                null,
                                                                                new LookAtEntity(
                                                                                        EntityArgument.getEntity(context, "facingEntity"),
                                                                                        EntityAnchorArgument.getAnchor(context, "facingAnchor")
                                                                                )
                                                                        ))))
                                                )
                                                .then(Commands.argument("facingLocation", Vec3Argument.vec3())
                                                        .executes(context -> teleportToPos(
                                                                context.getSource(),
                                                                EntityArgument.getEntities(context, "targets"),
                                                                context.getSource().getLevel(),
                                                                Vec3Argument.getCoordinates(context, "location"),
                                                                null,
                                                                new LookAtPosition(Vec3Argument.getVec3(context, "facingLocation"))
                                                        )))
                                        )
                                )
                                .then(Commands.argument("destination", EntityArgument.entity())
                                        .executes(context -> teleportToEntity(
                                                context.getSource(),
                                                EntityArgument.getEntities(context, "targets"),
                                                EntityArgument.getEntity(context, "destination")
                                        )))
                        )
        );
    }

    private static int teleportToEntity(CommandSourceStack source, Collection<? extends Entity> entities, Entity destination) throws CommandSyntaxException {
        for (Entity entity : entities) {
            performPortalTeleport(
                    source,
                    entity,
                    (ServerLevel) destination.level(),
                    destination.getX(), destination.getY(), destination.getZ(),
                    EnumSet.noneOf(Relative.class),
                    destination.getYRot(), destination.getXRot(),
                    null
            );
        }

        if (entities.size() == 1) {
            source.sendSuccess(
                    () -> Component.translatable(
                            "commands.teleport.success.entity.single",
                            entities.iterator().next().getDisplayName(),
                            destination.getDisplayName()
                    ),
                    true
            );
        } else {
            source.sendSuccess(
                    () -> Component.translatable("commands.teleport.success.entity.multiple", entities.size(), destination.getDisplayName()),
                    true
            );
        }

        return entities.size();
    }

    private static int teleportToPos(
            CommandSourceStack source,
            Collection<? extends Entity> entities,
            ServerLevel level,
            Coordinates destination,
            Coordinates rotation,
            LookAt lookAt
    ) throws CommandSyntaxException {
        Vec3 pos = destination.getPosition(source);
        Vec2 rot = rotation == null ? null : rotation.getRotation(source);

        for (Entity entity : entities) {
            Set<Relative> relatives = getRelatives(destination, rotation, entity.level().dimension() == level.dimension());
            if (rot == null) {
                performPortalTeleport(source, entity, level, pos.x, pos.y, pos.z, relatives, entity.getYRot(), entity.getXRot(), lookAt);
            } else {
                performPortalTeleport(source, entity, level, pos.x, pos.y, pos.z, relatives, rot.y, rot.x, lookAt);
            }
        }

        if (entities.size() == 1) {
            source.sendSuccess(
                    () -> Component.translatable(
                            "commands.teleport.success.location.single",
                            entities.iterator().next().getDisplayName(),
                            formatDouble(pos.x),
                            formatDouble(pos.y),
                            formatDouble(pos.z)
                    ),
                    true
            );
        } else {
            source.sendSuccess(
                    () -> Component.translatable(
                            "commands.teleport.success.location.multiple",
                            entities.size(), formatDouble(pos.x), formatDouble(pos.y), formatDouble(pos.z)
                    ),
                    true
            );
        }

        return entities.size();
    }

    private static Set<Relative> getRelatives(Coordinates destination, Coordinates rotation, boolean sameDimension) {
        Set<Relative> dir = Relative.direction(destination.isXRelative(), destination.isYRelative(), destination.isZRelative());
        Set<Relative> pos = sameDimension
                ? Relative.position(destination.isXRelative(), destination.isYRelative(), destination.isZRelative())
                : Set.of();
        Set<Relative> rot = rotation == null ? Relative.ROTATION : Relative.rotation(rotation.isYRelative(), rotation.isXRelative());
        return Relative.union(dir, pos, rot);
    }

    private static String formatDouble(double value) {
        return String.format(Locale.ROOT, "%f", value);
    }

    private static void performPortalTeleport(
            CommandSourceStack source,
            Entity victim,
            ServerLevel level,
            double x, double y, double z,
            Set<Relative> relatives,
            float yRot, float xRot,
            LookAt lookAt
    ) throws CommandSyntaxException {
        BlockPos blockPos = BlockPos.containing(x, y, z);
        if (!Level.isInSpawnableBounds(blockPos)) {
            throw INVALID_POSITION.create();
        }

        double relX = relatives.contains(Relative.X) ? x - victim.getX() : x;
        double relY = relatives.contains(Relative.Y) ? y - victim.getY() : y;
        double relZ = relatives.contains(Relative.Z) ? z - victim.getZ() : z;
        float relYRot = relatives.contains(Relative.Y_ROT) ? yRot - victim.getYRot() : yRot;
        float relXRot = relatives.contains(Relative.X_ROT) ? xRot - victim.getXRot() : xRot;
        float newYRot = Mth.wrapDegrees(relYRot);
        float newXRot = Mth.wrapDegrees(relXRot);

        boolean teleported = victim.teleportTo(level, relX, relY, relZ, relatives, newYRot, newXRot, true);
        if (!teleported) {
            return;
        }

        if (lookAt != null) {
            lookAt.perform(source, victim);
        }

        applyPortalEffect(victim, level);
    }

    private static void applyPortalEffect(Entity target, ServerLevel level) {
        if (target instanceof LivingEntity livingTarget) {
            livingTarget.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 20, 1, false, false, true));
            livingTarget.addEffect(new MobEffectInstance(MobEffects.NAUSEA, 80, 0, false, false, true));
        }

        if (target instanceof ServerPlayer player) {
            ClientboundSoundPacket soundPacket = new ClientboundSoundPacket(
                    PORTAL_TRAVEL_SOUND_HOLDER,
                    SoundSource.PLAYERS,
                    target.getX(), target.getY(), target.getZ(),
                    0.3f, 1.8f,
                    level.getRandom().nextLong()
            );
            player.connection.send(soundPacket);
        }

        level.sendParticles(
                ParticleTypes.PORTAL,
                target.getX(), target.getY() + 1, target.getZ(),
                100,
                0.5, 0.5, 0.5,
                0.1
        );
    }
}