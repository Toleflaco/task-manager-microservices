package com.mtole.task.activity;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository de eventos de auditoría.
 *
 * Extiende MongoRepository (no JpaRepository): el tipo de id es String
 * porque el _id de MongoDB es un ObjectId que Spring Data convierte
 * automáticamente a String.
 *
 * Por ahora solo hereda los métodos básicos de MongoRepository
 * (save, findAll, findById, deleteById, etc.). En sesiones siguientes
 * añadiremos derived queries para filtrar por userId, resourceType,
 * rangos temporales, etc.
 */

@Repository
public interface ActivityEventRepository extends MongoRepository<ActivityEvent,String>,ActivityEventRepositoryCustom {
}
