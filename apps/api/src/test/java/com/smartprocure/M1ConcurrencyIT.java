package com.smartprocure;

import com.smartprocure.sales.MockScenarioService;
import com.smartprocure.sales.SalesEventWorker;
import com.smartprocure.store.StoreService;
import com.smartprocure.tenant.TenantIdentityRepository;
import com.smartprocure.tenant.TenantPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(properties={"spring.flyway.schemas=m1_concurrency","spring.flyway.default-schema=m1_concurrency","spring.datasource.hikari.schema=m1_concurrency","app.mock.worker-enabled=false"})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="M0_TEST_DB_URL",matches=".+")
class M1ConcurrencyIT {
    static final UUID TENANT=UUID.fromString("11111111-1111-4111-8111-111111111111");
    @MockitoSpyBean JdbcTemplate jdbc;
    @Autowired StoreService stores;
    @Autowired MockScenarioService scenarios;
    @Autowired SalesEventWorker worker;
    @Autowired TenantIdentityRepository identities;
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",()->System.getenv("M0_TEST_DB_URL"));
        p.add("spring.datasource.username",()->System.getenv().getOrDefault("M0_TEST_DB_USER","postgres"));
        p.add("spring.datasource.password",()->System.getenv().getOrDefault("M0_TEST_DB_PASSWORD",""));
    }
    @BeforeEach void isolatePendingJobs() { jdbc.update("update outbox_events set state='DEAD' where event_type='M1_SALES_SYNC' and state not in ('DONE','DEAD','BLOCKED')"); }
    String queueOrder() {
        TenantPrincipal p=identities.findActive(UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1"),TENANT).orElseThrow();
        var store=stores.create(p,UUID.randomUUID().toString(),new StoreService.Create(StoreService.Platform.DOUYIN,"MOCK-"+UUID.randomUUID(),"合成并发回归店"));
        String external="MOCK-"+UUID.randomUUID();
        scenarios.generate(p,store.id(),UUID.randomUUID().toString(),new MockScenarioService.Input(MockScenarioService.Scenario.PAID,external));
        return external;
    }
    UUID outbox(String external) {
        return jdbc.queryForObject("select o.id from outbox_events o join inbox_events i on i.tenant_id=o.tenant_id and i.id=o.inbox_id where o.tenant_id=? and i.external_order_id=?",UUID.class,TENANT,external);
    }
    @Test void applyingFifthClaimLocksItsLeaseUntilBusinessAndCompletionCommitTogether() throws Exception {
        String external=queueOrder();UUID outbox=outbox(external);
        jdbc.update("update outbox_events set attempts=4 where tenant_id=? and id=?",TENANT,outbox);
        CountDownLatch applying=new CountDownLatch(1),resume=new CountDownLatch(1);
        AtomicBoolean once=new AtomicBoolean();
        doAnswer(invocation->{
            String sql=invocation.getArgument(0);
            if(sql.startsWith("select id,source_version,source_digest,version from sales_orders")&&once.compareAndSet(false,true)) {
                applying.countDown();
                if(!resume.await(10,TimeUnit.SECONDS))throw new AssertionError("Timed out waiting for lease contender");
            }
            return invocation.callRealMethod();
        }).when(jdbc).query(anyString(),any(RowMapper.class),any(Object[].class));
        boolean leaseChanged=false;
        try(var pool=Executors.newSingleThreadExecutor()) {
            var applyingWorker=pool.submit(worker::runOnce);
            try {
                assertThat(applying.await(10,TimeUnit.SECONDS)).as("worker reached its business transaction after lease validation").isTrue();
                try(var connection=DriverManager.getConnection(System.getenv("M0_TEST_DB_URL"),System.getenv().getOrDefault("M0_TEST_DB_USER","postgres"),System.getenv().getOrDefault("M0_TEST_DB_PASSWORD",""));var statement=connection.createStatement()) {
                    statement.execute("set search_path to m1_concurrency,public");statement.execute("set lock_timeout='200ms'");
                    try(var change=connection.prepareStatement("update outbox_events set lease_until=now()-interval '1 second' where tenant_id=? and id=?")) {
                        change.setObject(1,TENANT);change.setObject(2,outbox);
                        try { leaseChanged=change.executeUpdate()==1; }
                        catch(SQLException locked) { assertThat(locked.getSQLState()).isEqualTo("55P03"); }
                    }
                    if(leaseChanged) {
                        // Competing control-plane connection reproduces an exhausted-lease reclaim.
                        // No store/business lock is acquired: that is exactly what the lease must fence.
                        try(var reclaim=connection.prepareStatement("update outbox_events set state='DEAD',lease_token=null,lease_until=null,last_error_code='WORKER_LEASE_EXHAUSTED' where tenant_id=? and id=?")) { reclaim.setObject(1,TENANT);reclaim.setObject(2,outbox);assertThat(reclaim.executeUpdate()).isEqualTo(1); }
                        try(var reclaim=connection.prepareStatement("update inbox_events set state='DEAD' where tenant_id=? and id=(select inbox_id from outbox_events where tenant_id=? and id=?)")) { reclaim.setObject(1,TENANT);reclaim.setObject(2,TENANT);reclaim.setObject(3,outbox);assertThat(reclaim.executeUpdate()).isEqualTo(1); }
                    }
                }
            } finally { resume.countDown(); }
            applyingWorker.get(10,TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("select state from outbox_events where tenant_id=? and id=?",String.class,TENANT,outbox)).isEqualTo("DONE");
        assertThat(leaseChanged).as("the applying transaction must hold the outbox row lock").isFalse();
        assertThat(jdbc.queryForObject("select i.state from inbox_events i join outbox_events o on o.tenant_id=i.tenant_id and o.inbox_id=i.id where o.tenant_id=? and o.id=?",String.class,TENANT,outbox)).isEqualTo("PROCESSED");
        assertThat(jdbc.queryForObject("select count(*) from sales_orders where tenant_id=? and external_order_id=?",Integer.class,TENANT,external)).isEqualTo(1);
    }
    @Test void failedCompletionCompareAndSwapRollsBackOrderItemsTimelineAndInbox() {
        String external=queueOrder();UUID outbox=outbox(external);
        doAnswer(invocation->{
            if(((String)invocation.getArgument(0)).startsWith("update outbox_events set state='DONE'"))return 0;
            return invocation.callRealMethod();
        }).when(jdbc).update(anyString(),any(Object[].class));
        worker.runOnce();
        assertThat(jdbc.queryForObject("select count(*) from sales_orders where tenant_id=? and external_order_id=?",Integer.class,TENANT,external)).isZero();
        assertThat(jdbc.queryForObject("select i.state from inbox_events i join outbox_events o on o.tenant_id=i.tenant_id and o.inbox_id=i.id where o.tenant_id=? and o.id=?",String.class,TENANT,outbox)).isEqualTo("NEW");
        assertThat(jdbc.queryForObject("select state from outbox_events where tenant_id=? and id=?",String.class,TENANT,outbox)).isEqualTo("RETRY_WAIT");
    }
}
