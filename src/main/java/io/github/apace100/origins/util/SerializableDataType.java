package io.github.apace100.origins.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import io.github.apace100.origins.power.PowerType;
import io.github.apace100.origins.power.PowerTypeReference;
import io.github.apace100.origins.power.factory.condition.Condition;
import io.github.apace100.origins.power.factory.condition.ConditionType;
import io.github.apace100.origins.power.factory.condition.ConditionTypes;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.Fluid;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.tag.ServerTagManagerHolder;
import net.minecraft.tag.Tag;
import net.minecraft.util.Identifier;
import net.minecraft.util.registry.Registry;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

public class SerializableDataType<T> {

    public static final SerializableDataType<Integer> INT = new SerializableDataType<>(
        Integer.class,
        PacketByteBuf::writeInt,
        PacketByteBuf::readInt,
        JsonElement::getAsInt);

    public static final SerializableDataType<Boolean> BOOLEAN = new SerializableDataType<>(
        Boolean.class,
        PacketByteBuf::writeBoolean,
        PacketByteBuf::readBoolean,
        JsonElement::getAsBoolean);

    public static final SerializableDataType<Float> FLOAT = new SerializableDataType<>(
        Float.class,
        PacketByteBuf::writeFloat,
        PacketByteBuf::readFloat,
        JsonElement::getAsFloat);

    public static final SerializableDataType<Double> DOUBLE = new SerializableDataType<>(
        Double.class,
        PacketByteBuf::writeDouble,
        PacketByteBuf::readDouble,
        JsonElement::getAsDouble);

    public static final SerializableDataType<String> STRING = new SerializableDataType<>(
        String.class,
        PacketByteBuf::writeString,
        PacketByteBuf::readString,
        JsonElement::getAsString);

    public static final SerializableDataType<Identifier> IDENTIFIER = new SerializableDataType<>(
        Identifier.class,
        PacketByteBuf::writeIdentifier,
        PacketByteBuf::readIdentifier,
        (json) -> Identifier.tryParse(json.getAsString()));

    public static final SerializableDataType<EntityAttribute> ATTRIBUTE = SerializableDataType.registry(EntityAttribute.class, Registry.ATTRIBUTE);

    public static final SerializableDataType<EntityAttributeModifier> ATTRIBUTE_MODIFIER = new SerializableDataType<>(
        EntityAttributeModifier.class,
        SerializationHelper::writeAttributeModifier,
        SerializationHelper::readAttributeModifier,
        SerializationHelper::readAttributeModifier);

    public static final SerializableDataType<EntityAttributeModifier.Operation> MODIFIER_OPERATION = SerializableDataType.enumValue(EntityAttributeModifier.Operation.class);

    public static final SerializableDataType<AttributedEntityAttributeModifier> ATTRIBUTED_ATTRIBUTE_MODIFIER = SerializableDataType.compound(
        AttributedEntityAttributeModifier.class,
        new SerializableData()
            .add("attribute", ATTRIBUTE)
            .add("operation", MODIFIER_OPERATION)
            .add("value", DOUBLE)
            .add("name", STRING, "Unnamed EntityAttributeModifier"),
        dataInst -> new AttributedEntityAttributeModifier((EntityAttribute)dataInst.get("attribute"),
            new EntityAttributeModifier(
                dataInst.getString("name"),
                dataInst.getDouble("value"),
                (EntityAttributeModifier.Operation)dataInst.get("operation"))),
        (data, inst) -> {
            SerializableData.Instance dataInst = data.new Instance();
            dataInst.set("attribute", inst.getAttribute());
            dataInst.set("operation", inst.getModifier().getOperation());
            dataInst.set("value", inst.getModifier().getValue());
            dataInst.set("name", inst.getModifier().getName());
            return dataInst;
        });

    public static final SerializableDataType<List<EntityAttributeModifier>> ATTRIBUTE_MODIFIERS =
        SerializableDataType.list(ATTRIBUTE_MODIFIER);

    public static final SerializableDataType<List<AttributedEntityAttributeModifier>> ATTRIBUTED_ATTRIBUTE_MODIFIERS =
        SerializableDataType.list(ATTRIBUTED_ATTRIBUTE_MODIFIER);

    public static final SerializableDataType<PowerTypeReference> POWER_TYPE = SerializableDataType.wrap(
        PowerTypeReference.class, IDENTIFIER,
        PowerType::getIdentifier, PowerTypeReference::new);

    public static final SerializableDataType<StatusEffect> STATUS_EFFECT = SerializableDataType.registry(StatusEffect.class, Registry.STATUS_EFFECT);

    public static final SerializableDataType<Tag<Fluid>> FLUID_TAG = SerializableDataType.wrap(ClassUtil.castClass(Tag.class), IDENTIFIER,
        fluid -> ServerTagManagerHolder.getTagManager().getFluids().getTagId(fluid),
        SerializationHelper::getFluidTagFromId);

    public static final SerializableDataType<Comparison> COMPARISON = SerializableDataType.enumValue(Comparison.class,
        SerializationHelper.buildEnumMap(Comparison.class, Comparison::getComparisonString));

    public static final SerializableDataType<Condition<PlayerEntity>.Instance> PLAYER_CONDITION =
        SerializableDataType.condition(ClassUtil.castClass(Condition.Instance.class), ConditionTypes.PLAYER);

    private final Class<T> dataClass;
    private final BiConsumer<PacketByteBuf, T> send;
    private final Function<PacketByteBuf, T> receive;
    private final Function<JsonElement, T> read;

    public SerializableDataType(Class<T> dataClass,
                                BiConsumer<PacketByteBuf, T> send,
                                Function<PacketByteBuf, T> receive,
                                Function<JsonElement, T> read) {
        this.dataClass = dataClass;
        this.send = send;
        this.receive = receive;
        this.read = read;
    }

    public void send(PacketByteBuf buffer, Object value) {
        send.accept(buffer, cast(value));
    }

    public T receive(PacketByteBuf buffer) {
        return receive.apply(buffer);
    }

    public T read(JsonElement jsonElement) {
        return read.apply(jsonElement);
    }

    public T cast(Object data) {
        return dataClass.cast(data);
    }

    public static <T> SerializableDataType<List<T>> list(SerializableDataType<T> singleDataType) {
        return new SerializableDataType<>(ClassUtil.castClass(List.class), (buf, list) -> {
            buf.writeInt(list.size());
            for(T elem : list) {
                singleDataType.send(buf, elem);
            }
        }, (buf) -> {
            int count = buf.readInt();
            LinkedList<T> list = new LinkedList<>();
            for(int i = 0; i < count; i++) {
                list.add(singleDataType.receive(buf));
            }
            return list;
        }, (json) -> {
            LinkedList<T> list = new LinkedList<>();
            if(json.isJsonArray()) {
                for(JsonElement je : json.getAsJsonArray()) {
                    list.add(singleDataType.read(je));
                }
            } else {
                list.add(singleDataType.read(json));
            }
            return list;
        });
    }

    public static <T> SerializableDataType<T> registry(Class<T> dataClass, Registry<T> registry) {
        return new SerializableDataType<>(dataClass,
            (buf, t) -> buf.writeIdentifier(registry.getId(t)),
            (buf) -> registry.get(buf.readIdentifier()),
            (json) -> registry.get(Identifier.tryParse(json.getAsString())));
    }

    public static <T> SerializableDataType<T> compound(Class<T> dataClass, SerializableData data, Function<SerializableData.Instance, T> toInstance, BiFunction<SerializableData, T, SerializableData.Instance> toData) {
        return new SerializableDataType<>(dataClass,
            (buf, t) -> data.write(buf, toData.apply(data, t)),
            (buf) -> toInstance.apply(data.read(buf)),
            (json) -> toInstance.apply(data.read(json.getAsJsonObject())));
    }

    public static <T extends Enum<T>> SerializableDataType<T> enumValue(Class<T> dataClass) {
        return enumValue(dataClass, null);
    }

    public static <T extends Enum<T>> SerializableDataType<T> enumValue(Class<T> dataClass, HashMap<String, T> additionalMap) {
        return new SerializableDataType<>(dataClass,
            (buf, t) -> buf.writeInt(t.ordinal()),
            (buf) -> dataClass.getEnumConstants()[buf.readInt()],
            (json) -> {
                if(json.isJsonPrimitive()) {
                    JsonPrimitive primitive = json.getAsJsonPrimitive();
                    if(primitive.isNumber()) {
                        int enumOrdinal = primitive.getAsInt();
                        T[] enumValues = dataClass.getEnumConstants();
                        if(enumOrdinal < 0 || enumOrdinal >= enumValues.length) {
                            throw new JsonSyntaxException("Expected to be in the range of 0 - " + (enumValues.length - 1));
                        }
                        return enumValues[enumOrdinal];
                    } else if(primitive.isString()) {
                        String enumName = primitive.getAsString();
                        try {
                            T t = Enum.valueOf(dataClass, enumName);
                            return t;
                        } catch(IllegalArgumentException e0) {
                            try {
                                T t = Enum.valueOf(dataClass, enumName.toUpperCase());
                                return t;
                            } catch (IllegalArgumentException e1) {
                                try {
                                    if(additionalMap == null || !additionalMap.containsKey(enumName)) {
                                        throw new IllegalArgumentException();
                                    }
                                    T t = additionalMap.get(enumName);
                                    return t;
                                } catch (IllegalArgumentException e2) {
                                    T[] enumValues = dataClass.getEnumConstants();
                                    String stringOf = enumValues[0].name() + ", " + enumValues[0].name().toLowerCase();
                                    for(int i = 1; i < enumValues.length; i++) {
                                        stringOf += ", " + enumValues[i].name() + ", " + enumValues[i].name().toLowerCase();
                                    }
                                    throw new JsonSyntaxException("Expected value to be a string of: " + stringOf);
                                }
                            }
                        }
                    }
                }
                throw new JsonSyntaxException("Expected value to be either an integer or a string.");
            });
    }

    public static <T> SerializableDataType<Condition<T>.Instance> condition(Class<Condition<T>.Instance> dataClass, ConditionType<T> conditionType) {
        return new SerializableDataType<>(dataClass, conditionType::write, conditionType::read, conditionType::read);
    }

    public static <T, U> SerializableDataType<T> wrap(Class<T> dataClass, SerializableDataType<U> base, Function<T, U> toFunction, Function<U, T> fromFunction) {
        return new SerializableDataType<>(dataClass,
            (buf, t) -> base.send(buf, toFunction.apply(t)),
            (buf) -> fromFunction.apply(base.receive(buf)),
            (json) -> fromFunction.apply(base.read(json)));
    }
}
