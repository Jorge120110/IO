package com.example.georutassos.data.local.database;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;

import com.example.georutassos.data.local.converter.RoomTypeConverters;
import com.example.georutassos.data.local.dao.EmergencyOrderDao;
import com.example.georutassos.data.local.dao.GraphDao;
import com.example.georutassos.data.local.dao.UserDao;
import com.example.georutassos.data.local.entity.EmergencyOrder;
import com.example.georutassos.data.local.entity.GraphEdgeEntity;
import com.example.georutassos.data.local.entity.GraphNodeEntity;
import com.example.georutassos.data.local.entity.User;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Base de datos Room SQLite para GeoRuta SOS.
 * Integra las tablas operativas (usuarios y ordenes de emergencia) con las tablas del grafo
 * local para garantizar calculos de ruta con latencia minima.
 * <p>
 * Incluye barrera de inicializacion sincronizada ({@link #ensureOpenedSync()}) y modo de
 * diario {@link JournalMode#TRUNCATE} para evitar condiciones de carrera (SQLITE_READONLY_DBMOVED)
 * mientras se copia la base de datos de 55 MB desde assets en el primer arranque.
 */
@Database(
        entities = {
                User.class,
                EmergencyOrder.class,
                GraphNodeEntity.class,
                GraphEdgeEntity.class
        },
        version = 7,
        exportSchema = false
)
@TypeConverters({RoomTypeConverters.class})
public abstract class GeoRutaDatabase extends RoomDatabase {

    private static final String DATABASE_NAME = "georuta_sos_corredor_v7.db";
    private static final String ASSET_DATABASE_PATH = "databases/corredor_suroriente.db";

    private static final Object DB_INIT_LOCK = new Object();
    private static volatile boolean isDatabaseOpened = false;
    private static volatile GeoRutaDatabase INSTANCE;

    private static final ExecutorService rawWritePool = Executors.newFixedThreadPool(4);
    private static final ExecutorService rawQueryPool = Executors.newFixedThreadPool(4);
    private static final ExecutorService rawTransactionPool = Executors.newSingleThreadExecutor();

    /**
     * Ejecutor seguro que garantiza que ningun hilo de consulta o escritura acceda a SQLite
     * antes de que {@code SQLiteCopyOpenHelper} haya terminado de copiar y abrir el archivo
     * de 55 MB en el primer arranque.
     */
    public static final ExecutorService databaseWriteExecutor = new AbstractExecutorService() {
        @Override
        public void shutdown() {
            rawWritePool.shutdown();
        }

        @NonNull
        @Override
        public List<Runnable> shutdownNow() {
            return rawWritePool.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return rawWritePool.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return rawWritePool.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, @NonNull TimeUnit unit)
                throws InterruptedException {
            return rawWritePool.awaitTermination(timeout, unit);
        }

        @Override
        public void execute(@NonNull Runnable command) {
            rawWritePool.execute(() -> {
                GeoRutaDatabase db = INSTANCE;
                if (db != null) {
                    db.ensureOpenedSync();
                }
                command.run();
            });
        }
    };

    private static final Executor safeQueryExecutor = command -> rawQueryPool.execute(() -> {
        GeoRutaDatabase db = INSTANCE;
        if (db != null) {
            db.ensureOpenedSync();
        }
        command.run();
    });

    private static final Executor safeTransactionExecutor = command -> rawTransactionPool.execute(() -> {
        GeoRutaDatabase db = INSTANCE;
        if (db != null) {
            db.ensureOpenedSync();
        }
        command.run();
    });

    public abstract UserDao userDao();

    public abstract EmergencyOrderDao emergencyOrderDao();

    public abstract GraphDao graphDao();

    /**
     * Garantiza que la copia inicial desde assets y la apertura de SQLite se completen
     * en un unico hilo antes de permitir consultas concurrentes.
     */
    public void ensureOpenedSync() {
        if (!isDatabaseOpened) {
            synchronized (DB_INIT_LOCK) {
                if (!isDatabaseOpened) {
                    getOpenHelper().getWritableDatabase();
                    isDatabaseOpened = true;
                }
            }
        }
    }

    @NonNull
    public static GeoRutaDatabase getInstance(@NonNull final Context context) {
        if (INSTANCE == null) {
            synchronized (GeoRutaDatabase.class) {
                if (INSTANCE == null) {
                    final Context appContext = context.getApplicationContext();
                    cleanupLegacyDatabaseFiles(appContext);
                    INSTANCE = Room.databaseBuilder(
                                    appContext,
                                    GeoRutaDatabase.class,
                                    DATABASE_NAME
                            )
                            .createFromAsset(ASSET_DATABASE_PATH)
                            .setJournalMode(JournalMode.TRUNCATE)
                            .setQueryExecutor(safeQueryExecutor)
                            .setTransactionExecutor(safeTransactionExecutor)
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return INSTANCE;
    }

    private static void cleanupLegacyDatabaseFiles(@NonNull Context appContext) {
        String[] oldNames = new String[]{
                "georuta_sos_corredor.db",
                "georuta_sos_corredor_v2.db",
                "georuta_sos_corredor_v3.db",
                "georuta_sos_corredor_v4.db",
                "georuta_sos_corredor_v5.db",
                "georuta_sos_corredor_v6.db"
        };
        for (String oldName : oldNames) {
            try {
                appContext.deleteDatabase(oldName);
            } catch (Throwable ignored) {
            }
        }
    }
}
