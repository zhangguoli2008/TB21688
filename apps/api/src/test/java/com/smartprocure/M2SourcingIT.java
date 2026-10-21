package com.smartprocure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.flyway.schemas=m2_sourcing_test","spring.flyway.default-schema=m2_sourcing_test","spring.datasource.hikari.schema=m2_sourcing_test","app.mock.worker-enabled=false"})
@AutoConfigureMockMvc @ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="M0_TEST_DB_URL",matches=".+")
class M2SourcingIT {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",()->System.getenv("M0_TEST_DB_URL"));
        p.add("spring.datasource.username",()->System.getenv().getOrDefault("M0_TEST_DB_USER","m0test"));
        p.add("spring.datasource.password",()->System.getenv().getOrDefault("M0_TEST_DB_PASSWORD",""));
    }
    record Session(MockHttpSession session,String csrf) { }
    Session login(String persona)throws Exception {
        var first=mvc.perform(get("/api/v1/auth/csrf")).andReturn();
        var session=(MockHttpSession)first.getRequest().getSession();
        String token=mapper.readTree(first.getResponse().getContentAsString()).at("/data/token").asText();
        mvc.perform(post("/api/v1/auth/login").session(session).header("X-CSRF-TOKEN",token).contentType("application/json").content(mapper.writeValueAsString(Map.of("persona",persona)))).andExpect(status().isOk());
        var next=mvc.perform(get("/api/v1/auth/csrf").session(session)).andReturn();
        return new Session(session,mapper.readTree(next.getResponse().getContentAsString()).at("/data/token").asText());
    }
    JsonNode response(org.springframework.test.web.servlet.MvcResult result)throws Exception { return mapper.readTree(result.getResponse().getContentAsString()).get("data"); }
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder write(Session s,String method,String path,Object body)throws Exception {
        var request=switch(method){case "PATCH" -> patch(path);case "PUT" -> put(path);default -> post(path);};
        return request.session(s.session()).header("X-CSRF-TOKEN",s.csrf()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(mapper.writeValueAsString(body));
    }
    String store(Session s)throws Exception { return response(mvc.perform(write(s,"POST","/api/v1/stores",Map.of("platform","DOUYIN","externalShopId","MOCK-"+UUID.randomUUID(),"displayName","合成M2测试"))).andExpect(status().isOk()).andReturn()).get("id").asText(); }
    UUID item(String store,String sku)throws Exception {
        UUID order=UUID.randomUUID(),item=UUID.randomUUID();
        jdbc.update("insert into sales_orders(id,tenant_id,store_id,external_order_id,source_status,local_status,paid_amount_fen,source_version) values(?,'11111111-1111-4111-8111-111111111111',?,?,'PAID','VERIFIED_PAID',8600,1)",order,UUID.fromString(store),"MOCK-"+UUID.randomUUID());
        jdbc.update("insert into sales_order_items(id,tenant_id,sales_order_id,external_item_id,external_sku_id,title,qty,paid_allocated_fen) values(?,'11111111-1111-4111-8111-111111111111',?,'ITEM-1',?,'合成测试',2,8600)",item,order,sku);return item;
    }
    JsonNode mapping(Session s,String store,String offer,String sku)throws Exception { return response(mvc.perform(write(s,"POST","/api/v1/sku-mappings",Map.of("storeId",store,"salesSkuId",sku,"supplierOfferId",offer))).andExpect(status().isOk()).andReturn()); }
    void confirm(Session s,JsonNode mapping)throws Exception { mvc.perform(write(s,"PATCH","/api/v1/sku-mappings/"+mapping.get("id").asText(),Map.of("version",mapping.get("version").asLong(),"status","CONFIRMED","reviewAcknowledged",true))).andExpect(status().isOk()); }
    JsonNode evaluate(Session s,UUID item)throws Exception { return response(mvc.perform(write(s,"POST","/api/v1/purchase-intents/evaluate",Map.of("salesOrderItemId",item))).andExpect(status().isOk()).andReturn()); }
    @Test void mappingRequiresExplicitReviewAndVersionCas()throws Exception {
        var s=login("OWNER_A");String store=store(s);var m=mapping(s,store,"a3000000-0000-4000-8000-000000000001","MOCK-SKU-BLUE");
        assertThat(m.get("status").asText()).isEqualTo("REVIEW");assertThat(m.get("confirmedBy").isNull()).isTrue();
        mvc.perform(write(s,"PATCH","/api/v1/sku-mappings/"+m.get("id").asText(),Map.of("version",0,"status","CONFIRMED"))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVIEW_ACKNOWLEDGEMENT_REQUIRED"));
        confirm(s,m);
        mvc.perform(write(s,"PATCH","/api/v1/sku-mappings/"+m.get("id").asText(),Map.of("version",0,"status","PAUSED"))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        var list=response(mvc.perform(get("/api/v1/sku-mappings").session(s.session()).queryParam("storeId",store)).andExpect(status().isOk()).andReturn());
        assertThat(list.at("/items/0/confirmedBy").asText()).isEqualTo("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1");
        assertThat(list.at("/items/0/confirmedAt").asText()).endsWith("Z");
    }
    @Test void unmappedAndReviewItemsCannotPurchaseThenCheapestCompleteCostWins()throws Exception {
        var s=login("OWNER_A");String store=store(s);UUID item=item(store,"MOCK-SKU-BLUE");
        assertThat(evaluate(s,item).get("allowed").asBoolean()).isFalse();
        var first=mapping(s,store,"a3000000-0000-4000-8000-000000000001","MOCK-SKU-BLUE");
        assertThat(evaluate(s,item).get("allowed").asBoolean()).isFalse();
        confirm(s,first);var second=mapping(s,store,"a3000000-0000-4000-8000-000000000002","MOCK-SKU-BLUE");confirm(s,second);
        var evaluation=evaluate(s,item);assertThat(evaluation.get("allowed").asBoolean()).isTrue();
        // 2500*2+500=5500 beats 2400*2+900=5700 despite the higher unit price.
        assertThat(evaluation.at("/selectedQuote/totalFen").asLong()).isEqualTo(5500);
        assertThat(evaluation.get("mappingId").asText()).isEqualTo(first.get("id").asText());
        assertThat(evaluation.at("/candidates/0/estimatedProfitFen").asLong()).isEqualTo(3100);
    }
    @Test void mismatchedSalesSkuCannotBeConfirmedOrCreateIntent()throws Exception {
        var s=login("OWNER_A");String store=store(s);UUID item=item(store,"MOCK-SKU-WHITE");
        var mapping=mapping(s,store,"a3000000-0000-4000-8000-000000000001","MOCK-SKU-WHITE");
        assertThat(mapping.get("status").asText()).isEqualTo("REVIEW");
        mvc.perform(write(s,"PATCH","/api/v1/sku-mappings/"+mapping.get("id").asText(),Map.of("version",mapping.get("version").asLong(),"status","CONFIRMED","reviewAcknowledged",true)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SPECIFICATION_MISMATCH"));
        var after=response(mvc.perform(get("/api/v1/sku-mappings").session(s.session()).queryParam("storeId",store).queryParam("salesSkuId","MOCK-SKU-WHITE")).andExpect(status().isOk()).andReturn()).at("/items/0");
        assertThat(after.get("id").asText()).isEqualTo(mapping.get("id").asText());
        assertThat(after.get("status").asText()).isEqualTo("REVIEW");
        assertThat(after.get("version").asLong()).isEqualTo(mapping.get("version").asLong());
        assertThat(after.get("confirmedBy").isNull()).isTrue();
        assertThat(evaluate(s,item).get("allowed").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from purchase_intents where tenant_id='11111111-1111-4111-8111-111111111111' and sales_order_item_id=?",Integer.class,item)).isZero();
    }
    @Test void crossTenantOfferAndUnknownTenantInputAreRejected()throws Exception {
        var s=login("OWNER_A");String store=store(s);
        mvc.perform(write(s,"POST","/api/v1/sku-mappings",Map.of("storeId",store,"salesSkuId","MOCK-SKU-BLUE","supplierOfferId","b3000000-0000-4000-8000-000000000001"))).andExpect(status().isNotFound());
        mvc.perform(write(s,"POST","/api/v1/sku-mappings",Map.of("storeId",store,"salesSkuId","MOCK-SKU-BLUE","supplierOfferId","a3000000-0000-4000-8000-000000000001","tenantId","22222222-2222-4222-8222-222222222222"))).andExpect(status().isBadRequest());
    }
    @Test @org.springframework.transaction.annotation.Transactional void moneyRejectsFractionalAndStringIntegersRatherThanCoercing()throws Exception {
        var s=login("OWNER_A");String path="/api/v1/supplier-offers/a3000000-0000-4000-8000-000000000001/mock-config";
        JsonNode offer=response(mvc.perform(get("/api/v1/supplier-offers").session(s.session())).andExpect(status().isOk()).andReturn()).at("/items/0");
        mvc.perform(write(s,"PATCH",path,Map.of("version",offer.get("version").asLong(),"unitPriceFen",1.5))).andExpect(status().isBadRequest());
        mvc.perform(write(s,"PATCH",path,Map.of("version",offer.get("version").asLong(),"unitPriceFen","1701"))).andExpect(status().isBadRequest());
        mvc.perform(write(s,"PATCH",path,Map.of("version",offer.get("version").asLong(),"fault",1))).andExpect(status().isBadRequest());
    }
    String candidateCode(JsonNode evaluation) { return evaluation.at("/candidates/0/reasons/0/code").asText(); }
    @Test @org.springframework.transaction.annotation.Transactional void eachConfirmedAlternativeChecksStockMoqCipherRegionAndTimeout()throws Exception {
        var s=login("OWNER_A");String store=store(s);UUID item=item(store,"MOCK-SKU-BLUE");
        var mapping=mapping(s,store,"a3000000-0000-4000-8000-000000000001","MOCK-SKU-BLUE");confirm(s,mapping);
        jdbc.update("update supplier_offers set stock_qty=0 where id='a3000000-0000-4000-8000-000000000001'");
        assertThat(candidateCode(evaluate(s,item))).isEqualTo("STOCK_INSUFFICIENT");
        jdbc.update("update supplier_offers set stock_qty=100,min_order_qty=3 where id='a3000000-0000-4000-8000-000000000001'");
        assertThat(candidateCode(evaluate(s,item))).isEqualTo("MINIMUM_QUANTITY_NOT_MET");
        jdbc.update("update supplier_offers set min_order_qty=1,ciphertext_platforms='[\"TAOBAO\"]'::jsonb where id='a3000000-0000-4000-8000-000000000001'");
        assertThat(candidateCode(evaluate(s,item))).isEqualTo("CIPHERTEXT_UNSUPPORTED");
        jdbc.update("update supplier_offers set ciphertext_platforms='[\"DOUYIN\",\"TAOBAO\"]'::jsonb,regions='[]'::jsonb where id='a3000000-0000-4000-8000-000000000001'");
        assertThat(candidateCode(evaluate(s,item))).isEqualTo("REGION_UNSUPPORTED");
        jdbc.update("update supplier_offers set regions='[\"CN\"]'::jsonb,mock_behavior='QUOTE_TIMEOUT' where id='a3000000-0000-4000-8000-000000000001'");
        assertThat(candidateCode(evaluate(s,item))).isEqualTo("SUPPLIER_TIMEOUT");
        assertThat(jdbc.queryForObject("select count(*) from purchase_intents where sales_order_item_id=?",Integer.class,item)).isZero();
        // A confirmed alternative is evaluated independently and can satisfy the failed primary.
        var alternative=mapping(s,store,"a3000000-0000-4000-8000-000000000002","MOCK-SKU-BLUE");confirm(s,alternative);
        JsonNode result=evaluate(s,item);assertThat(result.get("allowed").asBoolean()).isTrue();assertThat(result.get("mappingId").asText()).isEqualTo(alternative.get("id").asText());
    }
    @Test @org.springframework.transaction.annotation.Transactional void exactFenProfitAndBothPolicyCapsApply()throws Exception {
        var s=login("OWNER_A");String store=store(s);UUID item=item(store,"MOCK-SKU-BLUE");
        confirm(s,mapping(s,store,"a3000000-0000-4000-8000-000000000001","MOCK-SKU-BLUE"));
        jdbc.update("update supplier_offers set last_unit_price_fen=1701,shipping_fen=109 where id='a3000000-0000-4000-8000-000000000001'");
        var exact=evaluate(s,item);assertThat(exact.at("/selectedQuote/totalFen").asLong()).isEqualTo(3511);
        jdbc.update("update procurement_policies set platform_fee_fen=333,reserve_fen=222,min_profit_fen=4535 where store_id=?",UUID.fromString(store));
        var below=evaluate(s,item);assertThat(candidateCode(below)).isEqualTo("PROFIT_BELOW_MINIMUM");assertThat(below.at("/candidates/0/estimatedProfitFen").asLong()).isEqualTo(4534);
        jdbc.update("update procurement_policies set min_profit_fen=4534 where store_id=?",UUID.fromString(store));
        assertThat(evaluate(s,item).get("allowed").asBoolean()).isTrue();
        jdbc.update("update procurement_policies set max_single_fen=3510 where tenant_id='11111111-1111-4111-8111-111111111111' and store_id is null");
        assertThat(candidateCode(evaluate(s,item))).isEqualTo("SINGLE_LIMIT_EXCEEDED");
        jdbc.update("update procurement_policies set max_single_fen=100000 where tenant_id='11111111-1111-4111-8111-111111111111' and store_id is null");
        jdbc.update("update procurement_policies set max_shipping_fen=108 where store_id=?",UUID.fromString(store));
        assertThat(candidateCode(evaluate(s,item))).isEqualTo("SHIPPING_LIMIT_EXCEEDED");
    }
    @Test @org.springframework.transaction.annotation.Transactional void quoteSnapshotStaysImmutableAndOverflowBlocksCleanly()throws Exception {
        var s=login("OWNER_A");String store=store(s);String offer="a3000000-0000-4000-8000-000000000001";
        JsonNode quote=response(mvc.perform(write(s,"POST","/api/v1/supplier-offers/"+offer+"/quotes",Map.of("storeId",store,"qty",2))).andExpect(status().isOk()).andReturn());
        jdbc.update("update supplier_offers set last_unit_price_fen=2600,version=version+1 where id=?",UUID.fromString(offer));
        String snapshot=jdbc.queryForObject("select snapshot::text from supplier_quotes where tenant_id='11111111-1111-4111-8111-111111111111' and id=?",String.class,UUID.fromString(quote.get("id").asText()));
        assertThat(mapper.readTree(snapshot).get("totalFen").asLong()).isEqualTo(5500);
        UUID item=item(store,"MOCK-SKU-BLUE");confirm(s,mapping(s,store,offer,"MOCK-SKU-BLUE"));
        jdbc.update("update supplier_offers set last_unit_price_fen=9223372036854775807 where id=?",UUID.fromString(offer));
        assertThat(candidateCode(evaluate(s,item))).isEqualTo("AMOUNT_OVERFLOW");
    }
    @Test void offerCatalogIsTenantIsolatedAndOwnerOnly()throws Exception {
        var a=login("OWNER_A");var b=login("OWNER_B");var support=login("SUPPORT_A");
        JsonNode offers=mapper.readTree(mvc.perform(get("/api/v1/supplier-offers").session(a.session())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).at("/data/items");
        JsonNode other=mapper.readTree(mvc.perform(get("/api/v1/supplier-offers").session(b.session())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).at("/data/items");
        assertThat(offers.size()).isGreaterThanOrEqualTo(3);
        assertThat(offers.get(0).get("id").asText()).isNotEqualTo(other.get(0).get("id").asText());
        mvc.perform(get("/api/v1/supplier-offers").session(support.session())).andExpect(status().isForbidden());
    }
}
