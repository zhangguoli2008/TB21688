package com.smartprocure.sales;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartprocure.audit.AuditService;
import com.smartprocure.common.ApiException;
import com.smartprocure.common.M1Support;
import com.smartprocure.store.StoreService;
import jakarta.validation.Validator;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class MockWebhookService {
    public record Event(@NotBlank @Size(max=128) @Pattern(regexp="[A-Za-z0-9_-]+") String eventId,@NotBlank @Size(max=80) @Pattern(regexp="[A-Za-z0-9_-]+") String externalOrderId,@Min(1) long sourceVersion,@NotNull Instant eventOccurredAt) { }
    public record Receipt(String eventId,boolean duplicate,String mode) { }
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final StoreService stores;
    private final AuditService audit;
    private final String key;
    public MockWebhookService(JdbcTemplate jdbc,ObjectMapper mapper,Validator validator,StoreService stores,AuditService audit,@Value("${app.mock.webhook-secret:MOCK_ONLY_LOCAL_WEBHOOK_KEY_NOT_FOR_PRODUCTION}") String key) { this.jdbc=jdbc;this.mapper=mapper;this.validator=validator;this.stores=stores;this.audit=audit;if(key.length()<16)throw new IllegalStateException("Mock webhook key must contain at least 16 characters");this.key=key; }
    public String sign(UUID integration,String timestamp,String nonce,String raw) {
        try { Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal((timestamp+"."+nonce+"."+integration+"."+raw).getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.GeneralSecurityException ex) { throw new IllegalStateException(ex); }
    }
    @Transactional public Receipt receive(UUID integration,String timestamp,String nonce,String signature,String raw) {
        return receive(integration,timestamp,nonce,signature,raw,Instant.now());
    }
    /** Used by the Mock simulator through precisely the same signature/nonce/inbox path. */
    public Receipt receive(UUID integration,String timestamp,String nonce,String signature,String raw,Instant availableAt) {
        stores.requireDemo();
        if(raw.getBytes(StandardCharsets.UTF_8).length>4096)throw M1Support.invalid();
        if(timestamp==null||!timestamp.matches("[0-9]{10}")||nonce==null||!nonce.matches("[a-fA-F0-9-]{36}")||signature==null||!signature.matches("[a-f0-9]{64}"))throw signatureError();
        UUID nonceId;try { nonceId=UUID.fromString(nonce); }catch(IllegalArgumentException ex){throw signatureError();}
        long seconds=Long.parseLong(timestamp),now=Instant.now().getEpochSecond();
        if(seconds<now-300||seconds>now+30||!MessageDigest.isEqual(sign(integration,timestamp,nonce,raw).getBytes(StandardCharsets.US_ASCII),signature.getBytes(StandardCharsets.US_ASCII)))throw signatureError();
        Event event; try { event=mapper.readValue(raw,Event.class); }catch(java.io.IOException ex){throw M1Support.invalid();}
        if(event==null||!validator.validate(event).isEmpty())throw M1Support.invalid();
        var binding=stores.integration(integration);stores.requireActive(binding.tenantId(),binding.storeId(),true);
        String digest=M1Support.digest(raw);
        var previous=jdbc.query("select payload_digest from inbox_events where tenant_id=? and store_id=? and provider='MOCK_SALES' and integration_ref=? and event_id=?",(r,n)->r.getString(1),binding.tenantId(),binding.storeId(),integration.toString(),event.eventId());
        if(!previous.isEmpty()) { if(!previous.getFirst().equals(digest))throw M1Support.conflict("EVENT_CONFLICT");return new Receipt(event.eventId(),true,"MOCK"); }
        if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from inbox_events where tenant_id=? and store_id=? and provider='MOCK_SALES' and integration_ref=? and nonce=?)",Boolean.class,binding.tenantId(),binding.storeId(),integration.toString(),nonceId)))throw M1Support.conflict("REPLAY_DETECTED");
        UUID inbox=UUID.randomUUID();
        jdbc.update("insert into inbox_events(id,provider,integration_ref,event_id,event_type,tenant_id,store_id,external_order_id,source_version,event_occurred_at,payload_digest,nonce) values(?,'MOCK_SALES',?,?,'SALES_ORDER_CHANGED',?,?,?,?,?,?,?)",inbox,integration.toString(),event.eventId(),binding.tenantId(),binding.storeId(),event.externalOrderId(),event.sourceVersion(),Timestamp.from(event.eventOccurredAt()),digest,nonceId);
        jdbc.update("insert into outbox_events(event_type,tenant_id,aggregate_id,correlation_id,payload,inbox_id,available_at) values('M1_SALES_SYNC',?,?,?,'{}'::jsonb,?,?)",binding.tenantId(),binding.storeId(),event.eventId(),inbox,Timestamp.from(availableAt));
        audit.object(binding.tenantId(),null,"MOCK_WEBHOOK_ACCEPTED","INBOX",inbox);
        return new Receipt(event.eventId(),false,"MOCK");
    }
    private static ApiException signatureError() { return new ApiException(401,"WEBHOOK_SIGNATURE_INVALID","Mock回调签名或时间无效"); }
}
