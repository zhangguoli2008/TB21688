package com.smartprocure;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.DriverManager;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named="M0_TEST_DB_URL",matches=".+")
class M1MigrationIT {
    @Test void freshDatabaseAndV1V2V3UpgradeHaveTheSameFourMigrationsAndPreserveData() throws Exception {
        String url=System.getenv("M0_TEST_DB_URL"),user=System.getenv().getOrDefault("M0_TEST_DB_USER","postgres"),password=System.getenv().getOrDefault("M0_TEST_DB_PASSWORD","");
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,12);
        String fresh="m1_fresh_"+suffix,upgrade="m1_upgrade_"+suffix;
        try {
            var first=Flyway.configure().dataSource(url,user,password).schemas(fresh).defaultSchema(fresh).load();
            assertThat(first.migrate().migrationsExecuted).isEqualTo(4);
            var old=Flyway.configure().dataSource(url,user,password).schemas(upgrade).defaultSchema(upgrade).target("1").load();
            assertThat(old.migrate().migrationsExecuted).isEqualTo(1);
            UUID tenant=UUID.randomUUID(),store=UUID.randomUUID();
            try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()) {
                statement.execute("insert into "+upgrade+".tenants(id,name) values('"+tenant+"','M2 upgrade synthetic tenant')");
                statement.execute("insert into "+upgrade+".stores(id,tenant_id,platform,external_shop_id,display_name) values('"+store+"','"+tenant+"','DOUYIN','M2-UPGRADE','Preserved synthetic store')");
            }
            var intermediate=Flyway.configure().dataSource(url,user,password).schemas(upgrade).defaultSchema(upgrade).target("2").load();
            assertThat(intermediate.migrate().migrationsExecuted).isEqualTo(1);
            var third=Flyway.configure().dataSource(url,user,password).schemas(upgrade).defaultSchema(upgrade).target("3").load();
            assertThat(third.migrate().migrationsExecuted).isEqualTo(1);
            var next=Flyway.configure().dataSource(url,user,password).schemas(upgrade).defaultSchema(upgrade).load();
            assertThat(next.migrate().migrationsExecuted).isEqualTo(1);assertThat(next.migrate().migrationsExecuted).isZero();
            try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()) {
                for(String schema:new String[]{fresh,upgrade}) {
                    try(var result=statement.executeQuery("select count(*) from "+schema+".flyway_schema_history where success and version in ('1','2','3','4')")) { result.next();assertThat(result.getInt(1)).isEqualTo(4); }
                    try(var result=statement.executeQuery("select count(*) from "+schema+".stores")) { result.next();assertThat(result.getInt(1)).isEqualTo(schema.equals(fresh)?0:1); }
                }
                try(var result=statement.executeQuery("select display_name,enabled from "+upgrade+".stores where tenant_id='"+tenant+"' and id='"+store+"'")) { assertThat(result.next()).isTrue();assertThat(result.getString(1)).isEqualTo("Preserved synthetic store");assertThat(result.getBoolean(2)).isTrue(); }
            }
        } finally {
            // Names are generated in this test, never supplied externally; no application schema is removed.
            try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()) { statement.execute("drop schema if exists "+fresh+" cascade");statement.execute("drop schema if exists "+upgrade+" cascade"); }
        }
    }
}
