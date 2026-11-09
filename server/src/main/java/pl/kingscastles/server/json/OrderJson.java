package pl.kingscastles.server.json;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pl.kingscastles.engine.order.Order;

/**
 * Orders travel as JSON with an "order" field (not "type": treaty orders already have a field called type), e.g. {"order":"build","settlementId":3,"building":"MARKET"}.
 * The engine has no JSON library, so the names are attached here.
 */
@Configuration
public class OrderJson {

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "order")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Order.SetTax.class, name = "setTax"),
            @JsonSubTypes.Type(value = Order.Build.class, name = "build"),
            @JsonSubTypes.Type(value = Order.BuildOnTile.class, name = "buildOnTile"),
            @JsonSubTypes.Type(value = Order.Recruit.class, name = "recruit"),
            @JsonSubTypes.Type(value = Order.Move.class, name = "move"),
            @JsonSubTypes.Type(value = Order.Attack.class, name = "attack"),
            @JsonSubTypes.Type(value = Order.FoundVillage.class, name = "foundVillage"),
            @JsonSubTypes.Type(value = Order.Gift.class, name = "gift"),
            @JsonSubTypes.Type(value = Order.MarketTrade.class, name = "market"),
            @JsonSubTypes.Type(value = Order.TakeLoan.class, name = "takeLoan"),
            @JsonSubTypes.Type(value = Order.BuyFreedom.class, name = "buyFreedom"),
            @JsonSubTypes.Type(value = Order.ProposeTreaty.class, name = "proposeTreaty"),
            @JsonSubTypes.Type(value = Order.AcceptTreaty.class, name = "acceptTreaty"),
            @JsonSubTypes.Type(value = Order.BreakTreaty.class, name = "breakTreaty")
    })
    interface OrderMixin {
    }

    public static ObjectMapper configure(ObjectMapper mapper) {
        mapper.addMixIn(Order.class, OrderMixin.class);
        mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        return mapper;
    }

    @Bean
    Jackson2ObjectMapperBuilderCustomizer orderMixin() {
        return b -> b.mixIn(Order.class, OrderMixin.class)
                .featuresToDisable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }
}
