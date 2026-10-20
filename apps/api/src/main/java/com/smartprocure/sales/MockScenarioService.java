package com.smartprocure.sales;

import com.smartprocure.audit.AuditService;
import com.smartprocure.common.M1Support;
import com.smartprocure.provider.SalesOrderProviderAdapter;
import com.smartprocure.store.StoreService;
import com.smartprocure.tenant.TenantPrincipal;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service
public class MockScenarioService {
    public enum Scenario { PAID,DUPLICATE,OUT_OF_ORDER,DELAYED,REFUNDED }
    public record Input(@NotNull Scenario scenario,@NotBlank @Size(max=80) @Pattern(regexp="[A-Za-z0-9_-]+") String externalOrderId) { }
    public record Result(Scenario scenario,String externalOrderId,int acceptedEvents,String mode) { }
    private final JdbcTemplate jdbc;
    private final StoreService stores;
    private final MockWebhookService webhook;
    private final M1Support support;
    private final AuditService audit;
    public MockScenarioService(JdbcTemplate jdbc,StoreService stores,MockWebhookService webhook,M1Support support,AuditService audit) { this.jdbc=jdbc;this.stores=stores;this.webhook=webhook;this.support=support;this.audit=audit; }
    @Transactional public Result generate(TenantPrincipal p,UUID storeId,String key,Input input) {
        var store=stores.get(p,storeId);stores.requireActive(p.currentTenantId(),storeId,true);
        return support.command(p.currentTenantId(),key,"SCENARIO:"+storeId,input,Result.class,()->{
            Long current=jdbc.queryForObject("select coalesce(max(source_version),0) from mock_order_authority where tenant_id=? and store_id=? and external_order_id=?",Long.class,p.currentTenantId(),storeId,input.externalOrderId());
            long version=current+(input.scenario()==Scenario.OUT_OF_ORDER?2:1);
            boolean refunded=input.scenario()==Scenario.REFUNDED||input.scenario()==Scenario.OUT_OF_ORDER;
            Instant now=Instant.now();
            var snapshot=new SalesOrderProviderAdapter.Snapshot(input.externalOrderId(),refunded?"REFUNDED":"PAID",version,"CNY",12900,now.minusSeconds(60),now.plusSeconds(86400),now,List.of(
                new SalesOrderProviderAdapter.Item("ITEM-1","MOCK-SKU-BLUE","合成示例 · 蓝色收纳盒",2,8600,refunded?2:0,0),
                new SalesOrderProviderAdapter.Item("ITEM-2","MOCK-SKU-WHITE","合成示例 · 白色收纳盒",1,4300,refunded?1:0,0)));
            String json=support.json(snapshot),digest=M1Support.digest(json);
            jdbc.update("insert into mock_order_authority(tenant_id,store_id,external_order_id,source_version,snapshot,snapshot_digest) values(?,?,?,?,?::jsonb,?) on conflict(tenant_id,store_id,external_order_id) do update set source_version=excluded.source_version,snapshot=excluded.snapshot,snapshot_digest=excluded.snapshot_digest where mock_order_authority.source_version<excluded.source_version",p.currentTenantId(),storeId,input.externalOrderId(),version,json,digest);
            Instant available=input.scenario()==Scenario.DELAYED?now.plusSeconds(2):now;
            var event=new MockWebhookService.Event("MOCK-"+UUID.randomUUID(),input.externalOrderId(),version,now);
            String raw=support.json(event),timestamp=Long.toString(now.getEpochSecond()),nonce=UUID.randomUUID().toString();
            int count=input.scenario()==Scenario.DUPLICATE?10:input.scenario()==Scenario.OUT_OF_ORDER?2:1;
            webhook.receive(store.integrationId(),timestamp,nonce,webhook.sign(store.integrationId(),timestamp,nonce,raw),raw,available);
            if(input.scenario()==Scenario.DUPLICATE)for(int i=1;i<10;i++)webhook.receive(store.integrationId(),timestamp,nonce,webhook.sign(store.integrationId(),timestamp,nonce,raw),raw,available);
            if(input.scenario()==Scenario.OUT_OF_ORDER) {
                var old=new MockWebhookService.Event("MOCK-"+UUID.randomUUID(),input.externalOrderId(),version-1,now.minusSeconds(60));
                String oldRaw=support.json(old),oldNonce=UUID.randomUUID().toString();webhook.receive(store.integrationId(),timestamp,oldNonce,webhook.sign(store.integrationId(),timestamp,oldNonce,oldRaw),oldRaw,available);
            }
            audit.object(p.currentTenantId(),p.userId(),"MOCK_SCENARIO_GENERATED","STORE",storeId);
            return new Result(input.scenario(),input.externalOrderId(),count,"MOCK");
        });
    }
}
