package com.smartprocure.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartprocure.tenant.TenantPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Shared M1 authorization and bounded paging; values always come from a verified principal. */
@Component
public class M1Support {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public M1Support(JdbcTemplate jdbc,ObjectMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }
    public record Page<T>(List<T> items,long total,int limit,int offset) { }
    public static void page(int limit,int offset) { if(limit<1||limit>100||offset<0||offset>10000) throw invalid(); }
    public static ApiException invalid() { return new ApiException(400,"VALIDATION_ERROR","请求参数无效"); }
    public static ApiException missing() { return new ApiException(404,"NOT_FOUND","资源不存在"); }
    public static ApiException conflict(String code) { return new ApiException(409,code,"当前状态不允许操作，请刷新后重试"); }
    public static boolean owner(TenantPrincipal p) { return p.roles().contains("OWNER"); }
    public String scope(TenantPrincipal p,String alias,List<Object> args) {
        args.add(p.currentTenantId());
        String sql=alias+".tenant_id=?";
        if(!owner(p)) { args.add(p.userId()); sql+=" and exists(select 1 from member_store_access msa join tenant_members tm on tm.tenant_id=msa.tenant_id and tm.id=msa.tenant_member_id where msa.tenant_id="+alias+".tenant_id and msa.store_id="+alias+".id and tm.user_id=? and tm.status='ACTIVE')"; }
        return sql;
    }
    public static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    public String json(Object value) { try { return mapper.writeValueAsString(value); } catch(java.io.IOException ex) { throw new IllegalStateException(ex); } }
    public <T> T command(UUID tenant,String key,String operation,Object request,Class<T> type,java.util.function.Supplier<T> action) {
        if(key==null||!key.matches("[A-Za-z0-9_-]{8,160}")) throw invalid();
        String hash=digest(json(request));
        jdbc.update("insert into m1_command_keys(tenant_id,command_key,operation,request_digest) values(?,?,?,?) on conflict do nothing",tenant,key,operation,hash);
        return jdbc.queryForObject("select operation,request_digest,response::text from m1_command_keys where tenant_id=? and command_key=? for update",(rs,n)->{
            if(!operation.equals(rs.getString(1))||!hash.equals(rs.getString(2))) throw conflict("IDEMPOTENCY_CONFLICT");
            String response=rs.getString(3);
            if(response!=null) { try { return mapper.readValue(response,type); } catch(java.io.IOException ex) { throw new IllegalStateException(ex); } }
            T result=action.get(); jdbc.update("update m1_command_keys set response=?::jsonb where tenant_id=? and command_key=?",json(result),tenant,key); return result;
        },tenant,key);
    }
}
