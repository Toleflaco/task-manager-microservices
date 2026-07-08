package com.mtole.task.config;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;

/**
 Configuración manual del cliente MongoDB y de la database factory.

 Construimos el MongoClient directamente desde el connection string
 en lugar de delegar en la autoconfig de Spring Boot, por un problema
 conocido en Spring Boot 4.0.6 + driver mongo 5.6.5 donde la URI llega
 al contexto pero no se aplica al cliente.

 Además, al construir el MongoClient manualmente, la cadena de
 autoconfig pierde conexión con la property spring.data.mongodb.database:
 la MongoDatabaseFactory que Spring Data crea por defecto cae en su
 fallback hardcoded "test" en lugar de leer la property. Por eso
 declaramos también la factory explícitamente apuntando a "taskmanager".
 */
@Configuration
public class MongoConfig {

    @Value("${spring.data.mongodb.uri}")
    private String mongoUri;

    @Bean
    public MongoClient mongoClient() {
        ConnectionString connectionString = new ConnectionString(mongoUri);
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(connectionString)
                .build();
        return MongoClients.create(settings);
    }

    @Bean
    public MongoDatabaseFactory mongoDatabaseFactory(MongoClient client) {
        return new SimpleMongoClientDatabaseFactory(client, "taskmanager");
    }
}
