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

    CREATE TABLE IF NOT EXISTS lan_rooms (
      room_id TEXT PRIMARY KEY,
      owner_player_id TEXT NOT NULL,
      owner_display_name TEXT NOT NULL DEFAULT '',
      mode TEXT NOT NULL DEFAULT 'room',
      allow_new_joins INTEGER NOT NULL DEFAULT 1,
      closed_at_ms INTEGER NOT NULL DEFAULT 0,
      acl_group TEXT NOT NULL DEFAULT '',
      network_secret TEXT NOT NULL DEFAULT '',
      created_at_ms INTEGER NOT NULL,
      updated_at_ms INTEGER NOT NULL,
      last_session_started_at_ms INTEGER NOT NULL DEFAULT 0
    );

    CREATE TABLE IF NOT EXISTS lan_sessions (
      session_id TEXT PRIMARY KEY,
      room_id TEXT NOT NULL,
      player_id TEXT NOT NULL,
      display_name TEXT NOT NULL DEFAULT '',
      client_version TEXT NOT NULL DEFAULT '',
      device_summary TEXT NOT NULL DEFAULT '',
      mode TEXT NOT NULL DEFAULT 'room',
      entry_node_url TEXT NOT NULL DEFAULT '',
      config_server_url TEXT NOT NULL DEFAULT '',
      acl_group TEXT NOT NULL DEFAULT '',
      network_secret TEXT NOT NULL DEFAULT '',
      session_state TEXT NOT NULL DEFAULT 'issued',
      assigned_ipv4_cidr TEXT NOT NULL DEFAULT '',
      relay_server_description TEXT NOT NULL DEFAULT '',
      created_at_ms INTEGER NOT NULL,
      updated_at_ms INTEGER NOT NULL,
      expires_at_ms INTEGER NOT NULL,
      ended_at_ms INTEGER NOT NULL DEFAULT 0,
      FOREIGN KEY(room_id) REFERENCES lan_rooms(room_id) ON DELETE CASCADE
    );

    CREATE INDEX IF NOT EXISTS idx_lan_sessions_room_id
      ON lan_sessions(room_id);

    CREATE INDEX IF NOT EXISTS idx_lan_sessions_room_player_created
      ON lan_sessions(room_id, player_id, created_at_ms DESC);

    CREATE INDEX IF NOT EXISTS idx_lan_sessions_room_active
      ON lan_sessions(room_id, ended_at_ms, expires_at_ms);
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
  await database.exec(`
    ALTER TABLE lan_rooms ADD COLUMN closed_at_ms INTEGER NOT NULL DEFAULT 0;
  `).catch((error) => {
    if (!/duplicate column name/i.test(String(error && error.message))) {
      throw error;
    }
  });
}

module.exports = {
  SqliteDatabase,
  openDatabase,
  initializeDatabase
};
