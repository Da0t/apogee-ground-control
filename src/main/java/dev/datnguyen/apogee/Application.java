package dev.datnguyen.apogee;

import dev.datnguyen.apogee.domain.*;
import dev.datnguyen.apogee.ground.*;
import dev.datnguyen.apogee.simulator.SimulatorMain;
import java.time.Clock;
import java.util.Arrays;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootApplication
@EnableScheduling
public class Application {
  public static void main(String[] args) throws Exception {
    if (Arrays.asList(args).contains("--simulator")) {
      SimulatorMain.main(args);
      return;
    }
    SpringApplication.run(Application.class, args);
  }

  @Bean
  TransactionTemplate transactions(DataSource source) {
    return new TransactionTemplate(new DataSourceTransactionManager(source));
  }

  @Bean(destroyMethod = "close")
  TcpLink link(
      @Value("${apogee.simulator.host}") String host, @Value("${apogee.simulator.port}") int port) {
    return new TcpLink(host, port);
  }

  @Bean
  GroundEngine engine(MissionStore store, TcpLink link) {
    return new GroundEngine(store, Clock.systemUTC(), link);
  }

  @Bean
  ApplicationRunner connect(GroundEngine engine, TcpLink link) {
    return args -> {
      engine.recover();
      link.start(engine::receive);
    };
  }
}
