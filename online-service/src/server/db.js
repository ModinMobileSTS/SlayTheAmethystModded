'use strict';

const fs = require('fs');
const path = require('path');
const sqlite3 = require('sqlite3');

class SqliteDatabase {
  constructor(db) {
    this.db = db;
  }

  exec(sql) {
    return new Promise((resolve, reject) => {
      this.db.exec(sql, (error) => {
        if (error) {
          reject(error);
          return;
        }
        resolve();
      });
    });
  }

  run(sql, params = []) {
    return new Promise((resolve, reject) => {
      this.db.run(sql, params, function onRun(error) {
        if (error) {
          reject(error);
          return;
        }
        resolve({
          changes: Number(this.changes) || 0,
          lastID: Number(this.lastID) || 0
        });
      });
    });
  }

  get(sql, params = []) {
    return new Promise((resolve, reject) => {
      this.db.get(sql, params, (error, row) => {
        if (error) {
          reject(error);
          return;
        }
        resolve(row || null);
      });
    });
  }

  all(sql, params = []) {
    return new Promise((resolve, reject) => {
      this.db.all(sql, params, (error, rows) => {
        if (error) {
          reject(error);
          return;
        }
        resolve(Array.isArray(rows) ? rows : []);
      });
    });
  }

  close() {
    return new Promise((resolve, reject) => {
      this.db.close((error) => {
        if (error) {
          reject(error);
          return;
        }
        resolve();
      });
    });
  }
}

async function openDatabase(dbPath) {
  const resolvedPath = path.resolve(dbPath);
  fs.mkdirSync(path.dirname(resolvedPath), { recursive: true });

  const sqlite = await new Promise((resolve, reject) => {
    const db = new sqlite3.Database(resolvedPath, (error) => {
      if (error) {
        reject(error);
        return;
      }
      resolve(db);
    });
  });
  sqlite.configure('busyTimeout', 5000);

  const database = new SqliteDatabase(sqlite);
  await initializeDatabase(database);
  return database;
}

async function initializeDatabase(database) {
  await database.exec(`
    PRAGMA journal_mode = WAL;
    PRAGMA foreign_keys = ON;
    PRAGMA busy_timeout = 5000;

    CREATE TABLE IF NOT EXISTS presence_sessions (
      client_id TEXT PRIMARY KEY,
      device_id TEXT NOT NULL DEFAULT '',
      id_type TEXT NOT NULL DEFAULT '',
      state TEXT NOT NULL DEFAULT 'game',
      player_name TEXT NOT NULL DEFAULT '',
      app_version TEXT NOT NULL DEFAULT '',
      device_model TEXT NOT NULL DEFAULT '',
      android_version TEXT NOT NULL DEFAULT '',
      first_seen_at_ms INTEGER NOT NULL,
      last_seen_at_ms INTEGER NOT NULL
    );

    CREATE TABLE IF NOT EXISTS presence_hourly_snapshots (
      snapshot_hour_ms INTEGER PRIMARY KEY,
      online INTEGER NOT NULL DEFAULT 0,
      by_state_json TEXT NOT NULL DEFAULT '{}',
      total_devices INTEGER NOT NULL DEFAULT 0,
      created_at_ms INTEGER NOT NULL,
      updated_at_ms INTEGER NOT NULL
    );

    DROP TABLE IF EXISTS lan_sessions;
    DROP TABLE IF EXISTS lan_rooms;
  `);
  await database.exec(`
    ALTER TABLE presence_sessions ADD COLUMN device_model TEXT NOT NULL DEFAULT '';
  `).catch((error) => {
    if (!/duplicate column name/i.test(String(error && error.message))) {
      throw error;
    }
  });
  await database.exec(`
    ALTER TABLE presence_sessions ADD COLUMN android_version TEXT NOT NULL DEFAULT '';
  `).catch((error) => {
    if (!/duplicate column name/i.test(String(error && error.message))) {
      throw error;
    }
  });
  // Keep historical device totals exact without counting the growing sessions
  // index on every heartbeat. The seed runs only once on existing databases.
  await database.exec(`
    CREATE INDEX IF NOT EXISTS idx_presence_last_seen_state
      ON presence_sessions(last_seen_at_ms, state);
    CREATE TABLE IF NOT EXISTS presence_totals (
      id INTEGER PRIMARY KEY CHECK (id = 1),
      total_devices INTEGER NOT NULL
    );
    INSERT INTO presence_totals (id, total_devices)
      SELECT 1, (SELECT COUNT(*) FROM presence_sessions)
      WHERE NOT EXISTS (SELECT 1 FROM presence_totals WHERE id = 1);
    CREATE TRIGGER IF NOT EXISTS presence_totals_insert
      AFTER INSERT ON presence_sessions BEGIN
        UPDATE presence_totals SET total_devices = total_devices + 1 WHERE id = 1;
      END;
    CREATE TRIGGER IF NOT EXISTS presence_totals_delete
      AFTER DELETE ON presence_sessions BEGIN
        UPDATE presence_totals SET total_devices = total_devices - 1 WHERE id = 1;
      END;
  `);
}

module.exports = {
  SqliteDatabase,
  openDatabase,
  initializeDatabase
};
