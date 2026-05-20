import os
import json
import re
import asyncio
from datetime import datetime, timezone
from mcstatus import JavaServer
from mcrcon import MCRcon
from telegram import Update
from telegram.ext import Application, CommandHandler, ContextTypes, MessageHandler, filters

ADMIN_CHAT_ID = int(os.environ.get("ADMIN_CHAT_ID", "0"))
RCON_PASSWORD = os.environ.get("RCON_PASSWORD", "")
RCON_PORT = 25575

TOKEN = os.environ.get("TELEGRAM_BOT_TOKEN")

SERVER_HOST = "srpmanhunt.ru"
SERVER_PORT = 25501
SERVER_IP = f"{SERVER_HOST}:{SERVER_PORT}"
DISCORD_OR_CHAT = "https://t.me/yourchannel"
SUBSCRIBERS_FILE = "subscribers.json"
STATS_FILE = "stats.json"
PLAYER_STATS_FILE = "player_stats.json"
ADMINS_FILE = "admins.json"
WARNS_FILE = "warns.json"
HISTORY_FILE = "history.json"
BANS_FILE = "bans.json"
CHECK_INTERVAL = 60

ROLE_LABELS = {
    "junior": "👮 Младший админ",
    "senior": "⭐ Старший админ",
    "creator": "👑 Создатель",
}
ROLE_LEVEL = {"junior": 1, "senior": 2, "creator": 4}
ROLE_ORDER = ["creator", "senior", "junior"]

UNIT_SECONDS = {
    "с": 1, "сек": 1, "секунд": 1, "секунды": 1, "секунду": 1,
    "м": 60, "мин": 60, "минут": 60, "минуты": 60, "минуту": 60,
    "ч": 3600, "час": 3600, "часа": 3600, "часов": 3600,
    "д": 86400, "день": 86400, "дня": 86400, "дней": 86400,
}


def parse_duration(parts):
    if len(parts) >= 2 and parts[0].isdigit():
        unit = parts[1].lower()
        if unit in UNIT_SECONDS:
            secs = int(parts[0]) * UNIT_SECONDS[unit]
            return secs, parts[2:]
    return None, parts


def fmt_duration(secs):
    if secs < 60:
        return f"{secs} сек."
    elif secs < 3600:
        return f"{secs // 60} мин."
    elif secs < 86400:
        return f"{secs // 3600} ч."
    else:
        return f"{secs // 86400} д."


server_was_online = None


def safe_save(path, data):
    temp_path = path + ".tmp"

    with open(temp_path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)

    os.replace(temp_path, path)

def load_stats():
    if os.path.exists(STATS_FILE):
        with open(STATS_FILE, "r") as f:
            return json.load(f)
    return {"last_online": None, "last_offline": None}


def save_stats(stats):
    safe_save(STATS_FILE, stats)


def fmt_time(ts):
    if not ts:
        return "нет данных"
    dt = datetime.fromtimestamp(ts, tz=timezone.utc)
    return dt.strftime("%d.%m.%Y %H:%M UTC")


def load_player_stats():
    if os.path.exists(PLAYER_STATS_FILE):
        with open(PLAYER_STATS_FILE, "r") as f:
            return json.load(f)
    return {}


def save_player_stats(data):
    safe_save(PLAYER_STATS_FILE, data)


def load_admins():
    if os.path.exists(ADMINS_FILE):
        with open(ADMINS_FILE, "r") as f:
            raw = json.load(f)
        # migrate old format {"id": "role"} → {"id": {"role": "...", "name": "..."}}
        migrated = {}
        for uid, val in raw.items():
            if isinstance(val, str):
                migrated[uid] = {"role": val, "name": uid}
            else:
                migrated[uid] = val
        return migrated
    return {}

def save_admins(data):
    safe_save(ADMINS_FILE, data)

def get_level(chat_id):
    if chat_id == ADMIN_CHAT_ID:
        return 4
    admins = load_admins()
    entry = admins.get(str(chat_id))
    if not entry:
        return 0
    role = entry["role"] if isinstance(entry, dict) else entry
    return ROLE_LEVEL.get(role, 0)


def load_bans():
    if os.path.exists(BANS_FILE):
        with open(BANS_FILE, "r") as f:
            return json.load(f)
    return {}


def save_bans(data):
    safe_save(BANS_FILE, data)


def ensure_creator_in_admins():
    if ADMIN_CHAT_ID == 0:
        return
    data = load_admins()
    entry = data.get(str(ADMIN_CHAT_ID))
    if not entry or (isinstance(entry, dict) and entry.get("role") != "creator"):
        data[str(ADMIN_CHAT_ID)] = {"role": "creator", "name": "Создатель"}
        save_admins(data)

def load_warns():
    if os.path.exists(WARNS_FILE):
        with open(WARNS_FILE, "r") as f:
            return json.load(f)
    return {}

def save_warns(data):
    with open(WARNS_FILE, "w") as f:
        json.dump(data, f)

def load_history():
    if os.path.exists(HISTORY_FILE):
        with open(HISTORY_FILE, "r") as f:
            return json.load(f)
    return []

def save_history(data):
    with open(HISTORY_FILE, "w") as f:
        json.dump(data, f)

def log_action(action, target, reason, by_id):
    history = load_history()
    history.append({
        "action": action,
        "target": target,
        "reason": reason,
        "by": by_id,
        "date": fmt_time(datetime.now(tz=timezone.utc).timestamp()),
    })
    save_history(history)

def load_subscribers():
    if os.path.exists(SUBSCRIBERS_FILE):
        with open(SUBSCRIBERS_FILE, "r") as f:
            return set(json.load(f))
    return set()


def save_subscribers(subs):
    with open(SUBSCRIBERS_FILE, "w") as f:
        json.dump(list(subs), f)


def is_server_online():
    try:
        server = JavaServer.lookup(f"{SERVER_HOST}:{SERVER_PORT}")
        s = server.status()
        names = [p.name for p in s.players.sample] if s.players.sample else []
        return True, s.players.online, s.players.max, names
    except Exception:
        return False, 0, 0, []


async def start(update: Update, context: ContextTypes.DEFAULT_TYPE):
    await update.message.reply_text(
        "🔥 Добро пожаловать в SRP Manhunt!\n\n"
        "Используй команды:\n"
        "/ip — IP сервера\n"
        "/rules — правила\n"
        "/event — ивенты\n"
        "/status — статус сервера\n"
        "/players — список игроков онлайн\n"
        "/notify — подписаться/отписаться от уведомлений\n"
        "/stats — статистика бота и сервера\n"
        "/top — топ по активности | /top kills — по убийствам\n"
        "/search <ник> — найти игрока по нику\n"
        "/team — кто в команде паразитов/людей\n"
        "/phase — текущая фаза паразитов\n\n"
        "🛡 Администрирование:\n"
        "/warn <ник> [причина] — выдать предупреждение\n"
        "/warns <ник> — список предупреждений\n"
        "/clearwarns <ник> — снять все предупреждения\n"
        "/ban <ник> [причина] — заблокировать игрока\n"
        "/unban <ник> — разблокировать игрока\n"
        "/admins — список администраторов\n"
        "/addadmin <id> <junior|senior> — назначить админа\n"
        "/removeadmin <id> — снять права\n"
        "/history — журнал действий администраторов"
    )


async def myid(update: Update, context: ContextTypes.DEFAULT_TYPE):
    user_id = update.effective_user.id
    chat_id = update.effective_chat.id
    await update.message.reply_text(
        f"🪪 Твой личный ID: `{user_id}`\n"
        f"💬 ID этого чата: `{chat_id}`",
        parse_mode="Markdown"
    )


async def ip(update: Update, context: ContextTypes.DEFAULT_TYPE):
    await update.message.reply_text(f"🎮 IP сервера SRP Manhunt:\n{SERVER_IP}")


async def rules(update: Update, context: ContextTypes.DEFAULT_TYPE):
    await update.message.reply_text(
        "📜 Правила сервера:\n"
        "1. Без читов\n"
        "2. Не ломать баланс модпака\n"
        "3. Уважать игроков\n"
        "4. Запрещены баг-эксплойты\n"
    )


async def event(update: Update, context: ContextTypes.DEFAULT_TYPE):
    await update.message.reply_text(
        "⚔️ Ивенты SRP Manhunt:\n"
        "- Манхант турниры\n"
        "- Выживание команд\n"
        "- Спец-режимы паразитов\n\n"
        "Следи за анонсами в канале!"
    )


async def players(update: Update, context: ContextTypes.DEFAULT_TYPE):
    try:
        server = JavaServer.lookup(f"{SERVER_HOST}:{SERVER_PORT}")
        s = server.status()
        online = s.players.online
        sample = s.players.sample
        if online == 0:
            await update.message.reply_text("👥 На сервере никого нет.")
        elif sample:
            names = "\n".join(f"• {p.name}" for p in sample)
            await update.message.reply_text(f"👥 Онлайн: {online}\n\n{names}")
        else:
            await update.message.reply_text(
                f"👥 Онлайн: {online}\n\n(Сервер скрывает список игроков)"
            )
    except Exception:
        await update.message.reply_text(
            f"🔴 Не удалось получить список игроков. Сервер недоступен.\n🌐 IP: {SERVER_IP}"
        )


async def status(update: Update, context: ContextTypes.DEFAULT_TYPE):
    try:
        server = JavaServer.lookup(f"{SERVER_HOST}:{SERVER_PORT}")
        s = server.status()
        await update.message.reply_text(
            f"🟢 Сервер онлайн!\n"
            f"👥 Игроков: {s.players.online}/{s.players.max}\n"
            f"📶 Пинг: {round(s.latency)} мс\n"
            f"🌐 IP: {SERVER_IP}"
        )
    except Exception:
        await update.message.reply_text(
            f"🔴 Сервер недоступен или офлайн.\n"
            f"🌐 IP: {SERVER_IP}"
        )


async def notify(update: Update, context: ContextTypes.DEFAULT_TYPE):
    chat_id = update.effective_chat.id
    subs = load_subscribers()
    if chat_id in subs:
        subs.remove(chat_id)
        save_subscribers(subs)
        await update.message.reply_text(
            "🔕 Уведомления отключены. Напиши /notify снова, чтобы включить."
        )
    else:
        subs.add(chat_id)
        save_subscribers(subs)
        await update.message.reply_text(
            "🔔 Уведомления включены! Ты получишь сообщение, когда сервер "
            "включится или выключится."
        )


async def stats(update: Update, context: ContextTypes.DEFAULT_TYPE):
    subs = load_subscribers()
    data = load_stats()
    online, count, max_count, _ = is_server_online()
    current = "🟢 Онлайн" if online else "🔴 Офлайн"
    await update.message.reply_text(
        f"📊 Статистика SRP Manhunt\n\n"
        f"🖥 Сервер сейчас: {current}\n"
        f"👥 Подписчиков на уведомления: {len(subs)}\n\n"
        f"🟢 Последний раз онлайн: {fmt_time(data['last_online'])}\n"
        f"🔴 Последний раз офлайн: {fmt_time(data['last_offline'])}"
    )


async def top(update: Update, context: ContextTypes.DEFAULT_TYPE):
    player_data = load_player_stats()
    if not player_data:
        await update.message.reply_text(
            "📭 Пока нет данных. Бот только начал отслеживать игроков — "
            "загляни позже!"
        )
        return

    mode = context.args[0].lower() if context.args else ""
    medals = ["🥇", "🥈", "🥉"]

    if mode == "kills":
        sorted_players = sorted(
            player_data.items(),
            key=lambda x: x[1].get("kills", 0) if isinstance(x[1], dict) else 0,
            reverse=True
        )[:10]
        lines = []
        for i, (name, entry) in enumerate(sorted_players):
            kills = entry.get("kills", 0) if isinstance(entry, dict) else 0
            prefix = medals[i] if i < 3 else f"{i + 1}."
            lines.append(f"{prefix} {name} — {kills} убийств")
        await update.message.reply_text(
            "☠️ Топ игроков по убийствам:\n\n" + "\n".join(lines)
        )
    else:
        sorted_players = sorted(
            player_data.items(),
            key=lambda x: x[1]["count"] if isinstance(x[1], dict) else x[1],
            reverse=True
        )[:10]
        lines = []
        for i, (name, entry) in enumerate(sorted_players):
            count = entry["count"] if isinstance(entry, dict) else entry
            kills = entry.get("kills", 0) if isinstance(entry, dict) else 0
            prefix = medals[i] if i < 3 else f"{i + 1}."
            lines.append(f"{prefix} {name} — {count} раз | ☠️ {kills} убийств")
        await update.message.reply_text(
            "🏆 Топ игроков SRP Manhunt\n"
            "(по активности | убийствам)\n\n"
            + "\n".join(lines)
            + "\n\nДля топа по убийствам: /top kills"
        )


async def search(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if not context.args:
        await update.message.reply_text("Использование: /search <ник>")
        return

    query = context.args[0].lower()
    player_data = load_player_stats()

    matches = {
        name: entry for name, entry in player_data.items()
        if query in name.lower()
    }

    if not matches:
        await update.message.reply_text(
            f"❌ Игрок «{context.args[0]}» не найден в базе.\n"
            "Возможно, он ещё не был замечен на сервере."
        )
        return

    lines = []
    for name, entry in sorted(matches.items(), key=lambda x: x[1]["count"] if isinstance(x[1], dict) else x[1], reverse=True):
        count = entry["count"] if isinstance(entry, dict) else entry
        last_seen = entry.get("last_seen") if isinstance(entry, dict) else None
        kills = entry.get("kills", 0) if isinstance(entry, dict) else 0
        lines.append(
            f"👤 {name}\n"
            f"   🔁 Замечен: {count} раз\n"
            f"   ☠️ Убийств: {kills}\n"
            f"   🕒 Последний раз: {fmt_time(last_seen)}"
        )

    await update.message.reply_text(
        f"🔍 Результаты поиска «{context.args[0]}»:\n\n" + "\n\n".join(lines)
    )


TEAM_LABELS = {
    "parasites": "🦠 Паразит",
    "humans":    "🧑 Человек",
}

PHASE_LABELS = {
    "1": "🌑 Фаза 1 — Начало заражения",
    "2": "🌒 Фаза 2 — Распространение",
    "3": "🌓 Фаза 3 — Мутация",
    "4": "🌔 Фаза 4 — Доминирование",
    "5": "🌕 Фаза 5 — Апокалипсис",
}

current_phase = None


def strip_color_codes(text):
    return re.sub(r'§.', '', text)


def rcon_command(command):
    if not RCON_PASSWORD:
        return None, "RCON пароль не настроен."
    try:
        with MCRcon(SERVER_HOST, RCON_PASSWORD, port=RCON_PORT) as rcon:
            resp = strip_color_codes(rcon.command(command)).strip()
            return resp, None
    except Exception as e:
        return None, str(e)


def get_phase():
    resp, error = rcon_command("srpevolution getphase")
    if error:
        return None, error
    match = re.search(r'\d+', resp)
    if match:
        return match.group(), None
    return None, f"Неизвестный ответ: {resp}"


def fetch_kill_counts(names):
    """Returns {player_name: kill_count} for the given list of players."""
    counts = {}
    if not RCON_PASSWORD or not names:
        return counts
    try:
        with MCRcon(SERVER_HOST, RCON_PASSWORD, port=RCON_PORT) as rcon:
            for name in names:
                resp = strip_color_codes(rcon.command(f"scoreboard players get {name} kills")).strip()
                match = re.search(r'(\d+)', resp)
                if match:
                    counts[name] = int(match.group(1))
    except Exception:
        pass
    return counts


def get_team_members():
    results = {}
    if not RCON_PASSWORD:
        return None, "RCON пароль не настроен."
    try:
        with MCRcon(SERVER_HOST, RCON_PASSWORD, port=RCON_PORT) as rcon:
            for team_key in TEAM_LABELS:
                resp = strip_color_codes(rcon.command(f"scoreboard teams list {team_key}"))
                members = re.findall(r'\[([^\]]+)\]', resp)
                names = [n.strip() for n in members[0].split(',') if n.strip()] if members else []
                results[team_key] = names
        return results, None
    except Exception as e:
        return None, str(e)


async def team(update: Update, context: ContextTypes.DEFAULT_TYPE):
    await update.message.reply_text("⏳ Запрашиваю данные команд...")
    teams, error = get_team_members()

    if error:
        await update.message.reply_text(
            f"🔴 Не удалось получить данные команд.\n"
            f"Причина: {error}\n\n"
            "Убедись, что RCON включён на сервере (`enable-rcon=true` в server.properties)."
        )
        return

    lines = []
    total = 0
    for team_key, label in TEAM_LABELS.items():
        members = teams.get(team_key, [])
        total += len(members)
        if members:
            names = "\n".join(f"  • {n}" for n in members)
            lines.append(f"{label} ({len(members)}):\n{names}")
        else:
            lines.append(f"{label}: никого нет")

    if total == 0:
        await update.message.reply_text("👻 Сейчас никто не играет.")
        return

    await update.message.reply_text(
        "⚔️ Текущие команды на сервере:\n\n" + "\n\n".join(lines)
    )


async def phase(update: Update, context: ContextTypes.DEFAULT_TYPE):
    phase_num, error = get_phase()
    if error:
        await update.message.reply_text(
            f"🔴 Не удалось получить фазу паразитов.\nПричина: {error}"
        )
        return
    label = PHASE_LABELS.get(phase_num, f"❓ Фаза {phase_num}")
    await update.message.reply_text(f"🧬 Текущая фаза паразитов:\n{label}")



async def warn(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if get_level(update.effective_user.id) < 1:
        await update.message.reply_text("⛔ Недостаточно прав.")
        return
    if not context.args:
        await update.message.reply_text("Использование: /warn <ник> [длительность единица] [причина]\nПример: /warn Steve 30 минут токсичность")
        return
    player = context.args[0]
    rest = context.args[1:]
    duration_secs, rest = parse_duration(rest)
    reason = " ".join(rest) if rest else "Без причины"
    now_ts = datetime.now(tz=timezone.utc).timestamp()
    entry = {
        "reason": reason,
        "date": fmt_time(now_ts),
        "by": update.effective_user.id,
        "expires": (now_ts + duration_secs) if duration_secs else None
    }
    warns_data = load_warns()
    warns_data.setdefault(player, []).append(entry)
    save_warns(warns_data)
    count = len(warns_data[player])
    log_action("warn", player, reason, update.effective_user.id)
    dur_str = f" на {fmt_duration(duration_secs)}" if duration_secs else " навсегда"
    await update.message.reply_text(
        f"⚠️ Игрок {player} получил предупреждение{dur_str} ({count}).\n"
        f"Причина: {reason}"
    )


async def warns(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if get_level(update.effective_user.id) < 1:
        await update.message.reply_text("⛔ Недостаточно прав.")
        return
    if not context.args:
        await update.message.reply_text("Использование: /warns <ник>")
        return
    player = context.args[0]
    data = load_warns()
    now_ts = datetime.now(tz=timezone.utc).timestamp()
    player_warns = [w for w in data.get(player, []) if not w.get("expires") or w["expires"] > now_ts]
    if not player_warns:
        await update.message.reply_text(f"✅ У игрока {player} нет активных предупреждений.")
        return
    lines = []
    for i, w in enumerate(player_warns):
        exp = f" (до {fmt_time(w['expires'])})" if w.get("expires") else " (навсегда)"
        lines.append(f"{i+1}. {w['reason']} | {w['date']}{exp}")
    await update.message.reply_text(
        f"⚠️ Предупреждения {player} ({len(player_warns)}):\n\n" + "\n".join(lines)
    )


async def clearwarns(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if get_level(update.effective_user.id) < 2:
        await update.message.reply_text("⛔ Только старший админ может снимать предупреждения.")
        return
    if not context.args:
        await update.message.reply_text("Использование: /clearwarns <ник>")
        return
    player = context.args[0]
    data = load_warns()
    if player not in data or not data[player]:
        await update.message.reply_text(f"✅ У игрока {player} нет предупреждений.")
        return
    data[player] = []
    save_warns(data)
    await update.message.reply_text(f"✅ Предупреждения игрока {player} сняты.")


async def ban(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if get_level(update.effective_user.id) < 2:
        await update.message.reply_text("⛔ Только старший админ может банить игроков.")
        return
    if not context.args:
        await update.message.reply_text("Использование: /ban <ник> [длительность единица] [причина]\nПример: /ban Steve 24 часа читы")
        return
    player = context.args[0]
    rest = context.args[1:]
    duration_secs, rest = parse_duration(rest)
    reason = " ".join(rest) if rest else "Нарушение правил"
    resp, error = rcon_command(f"ban {player} {reason}")
    if error:
        await update.message.reply_text(f"🔴 Ошибка RCON: {error}")
        return
    if duration_secs:
        now_ts = datetime.now(tz=timezone.utc).timestamp()
        bans_data = load_bans()
        bans_data[player] = {"reason": reason, "expires": now_ts + duration_secs, "by": update.effective_user.id}
        save_bans(bans_data)
    log_action("ban", player, reason, update.effective_user.id)
    dur_str = f" на {fmt_duration(duration_secs)}" if duration_secs else " навсегда"
    await update.message.reply_text(f"🔨 Игрок {player} заблокирован{dur_str}.\nПричина: {reason}")


async def unban(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if get_level(update.effective_user.id) < 2:
        await update.message.reply_text("⛔ Только старший админ может разблокировать игроков.")
        return
    if not context.args:
        await update.message.reply_text("Использование: /unban <ник>")
        return
    player = context.args[0]
    resp, error = rcon_command(f"pardon {player}")
    if error:
        await update.message.reply_text(f"🔴 Ошибка RCON: {error}")
        return
    log_action("unban", player, "-", update.effective_user.id)
    await update.message.reply_text(f"✅ Игрок {player} разблокирован.")


async def admins(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if get_level(update.effective_user.id) < 3:
        await update.message.reply_text("⛔ Только главный админ может просматривать список.")
        return
    data = load_admins()
    if not data:
        await update.message.reply_text("📭 Список администраторов пуст.")
        return
    grouped = {r: [] for r in ROLE_ORDER}
    for uid, entry in data.items():
        role = entry["role"] if isinstance(entry, dict) else entry
        name = entry.get("name", uid) if isinstance(entry, dict) else uid
        link = f'<a href="tg://user?id={uid}">{name}</a>'
        if role in grouped:
            grouped[role].append(link)
    lines = []
    for role in ROLE_ORDER:
        if grouped[role]:
            label = ROLE_LABELS[role]
            lines.append(label + ":\n" + "\n".join(f"  • {l}" for l in grouped[role]))
    await update.message.reply_text("🛡 Администраторы:\n\n" + "\n\n".join(lines), parse_mode="HTML")


async def addadmin(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if get_level(update.effective_user.id) < 3:
        await update.message.reply_text("⛔ Только главный админ может назначать администраторов.")
        return
    if len(context.args) < 2:
        await update.message.reply_text("Использование: /addadmin <telegram_id> <junior|senior>")
        return
    uid, role = context.args[0], context.args[1].lower()
    if role not in ROLE_LEVEL:
        await update.message.reply_text("❌ Роль должна быть: junior, senior или creator")
        return
    if role == "creator" and get_level(update.effective_user.id) < 4:
        await update.message.reply_text("⛔ Только Создатель может назначать Создателей.")
        return
    name = context.args[2] if len(context.args) > 2 else uid
    data = load_admins()
    data[uid] = {"role": role, "name": name}
    save_admins(data)
    await update.message.reply_text(f"✅ {name} назначен: {ROLE_LABELS[role]}")


async def removeadmin(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if get_level(update.effective_user.id) < 3:
        await update.message.reply_text("⛔ Только главный админ может снимать права.")
        return
    if not context.args:
        await update.message.reply_text("Использование: /removeadmin <telegram_id>")
        return
    uid = context.args[0]
    data = load_admins()
    if uid not in data:
        await update.message.reply_text(f"❌ Пользователь {uid} не найден в списке админов.")
        return
    entry = data.pop(uid)
    role = entry["role"] if isinstance(entry, dict) else entry
    name = entry.get("name", uid) if isinstance(entry, dict) else uid
    save_admins(data)
    await update.message.reply_text(f"✅ Права {ROLE_LABELS[role]} сняты с {name}.")


async def history(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if get_level(update.effective_user.id) < 1:
        await update.message.reply_text("⛔ Недостаточно прав.")
        return
    data = load_history()
    if not data:
        await update.message.reply_text("📭 История действий пуста.")
        return
    action_icons = {"warn": "⚠️", "ban": "🔨", "unban": "✅", "promote": "⬆️", "demote": "⬇️"}
    lines = []
    for entry in reversed(data[-20:]):
        icon = action_icons.get(entry["action"], "•")
        lines.append(
            f"{icon} {entry['action'].upper()} | {entry['target']}\n"
            f"   Причина: {entry['reason']} | {entry['date']}"
        )
    await update.message.reply_text("📋 Последние действия администраторов:\n\n" + "\n\n".join(lines))


async def keyword_action(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if not update.message or not update.message.reply_to_message:
        return

    caller_id = update.effective_user.id
    text = update.message.text.strip().lower()
    parts = text.split()
    keyword = parts[0]
    extra = parts[1:] if len(parts) > 1 else []

    replied = update.message.reply_to_message
    target_id = None
    target_name = None

    if replied.forward_origin:
        fo = replied.forward_origin
        if hasattr(fo, "sender_user") and fo.sender_user:
            target_id = fo.sender_user.id
            target_name = fo.sender_user.username or fo.sender_user.first_name or str(target_id)
        elif hasattr(fo, "sender_user_name") and fo.sender_user_name:
            target_name = fo.sender_user_name
        elif hasattr(fo, "chat") and fo.chat:
            target_id = fo.chat.id
            target_name = fo.chat.username or fo.chat.title or str(target_id)

    if not target_name and replied.from_user:
        target_id = replied.from_user.id
        target_name = replied.from_user.username or replied.from_user.first_name or str(target_id)

    if not target_name:
        await update.message.reply_text("❌ Не удалось определить пользователя.")
        return

    if keyword == "бан":
        if get_level(caller_id) < 2:
            await update.message.reply_text("⛔ Только старший админ может банить.")
            return
        duration_secs, rest = parse_duration(extra)
        reason = " ".join(rest) if rest else "Нарушение правил"
        resp, error = rcon_command(f"ban {target_name} {reason}")
        if error:
            await update.message.reply_text(f"🔴 Ошибка RCON: {error}")
            return
        if duration_secs:
            now_ts = datetime.now(tz=timezone.utc).timestamp()
            bans_data = load_bans()
            bans_data[target_name] = {"reason": reason, "expires": now_ts + duration_secs, "by": caller_id}
            save_bans(bans_data)
        log_action("ban", target_name, reason, caller_id)
        dur_str = f" на {fmt_duration(duration_secs)}" if duration_secs else " навсегда"
        await update.message.reply_text(f"🔨 {target_name} заблокирован{dur_str}.\nПричина: {reason}")

    elif keyword == "варн":
        if get_level(caller_id) < 1:
            await update.message.reply_text("⛔ Недостаточно прав.")
            return
        duration_secs, rest = parse_duration(extra)
        reason = " ".join(rest) if rest else "Без причины"
        now_ts = datetime.now(tz=timezone.utc).timestamp()
        entry = {
            "reason": reason,
            "date": fmt_time(now_ts),
            "by": caller_id,
            "expires": (now_ts + duration_secs) if duration_secs else None
        }
        warns_data = load_warns()
        warns_data.setdefault(target_name, []).append(entry)
        save_warns(warns_data)
        count = len(warns_data[target_name])
        log_action("warn", target_name, reason, caller_id)
        dur_str = f" на {fmt_duration(duration_secs)}" if duration_secs else " навсегда"
        await update.message.reply_text(f"⚠️ {target_name} получил предупреждение{dur_str} ({count}).\nПричина: {reason}")

    elif keyword == "повысить":
        if get_level(caller_id) < 3:
            await update.message.reply_text("⛔ Только главный админ может повышать.")
            return
        admins_data = load_admins()
        current_entry = admins_data.get(str(target_id))
        current_role = current_entry["role"] if isinstance(current_entry, dict) else current_entry
        if extra and extra[0] in ROLE_LEVEL:
            new_role = extra[0]
        elif current_role == "junior":
            new_role = "senior"
        else:
            new_role = "junior"
        admins_data[str(target_id)] = {"role": new_role, "name": target_name}
        save_admins(admins_data)
        log_action("promote", target_name, new_role, caller_id)
        await update.message.reply_text(f"⬆️ {target_name} повышен до {ROLE_LABELS[new_role]}.")

    elif keyword == "понизить":
        if get_level(caller_id) < 3:
            await update.message.reply_text("⛔ Только главный админ может понижать.")
            return
        admins_data = load_admins()
        current_entry = admins_data.get(str(target_id))
        current_role = current_entry["role"] if isinstance(current_entry, dict) else current_entry
        if current_role == "senior":
            admins_data[str(target_id)] = {"role": "junior", "name": target_name}
            save_admins(admins_data)
            log_action("demote", target_name, "senior → junior", caller_id)
            await update.message.reply_text(f"⬇️ {target_name} понижен до {ROLE_LABELS['junior']}.")
        elif current_role == "junior":
            admins_data.pop(str(target_id), None)
            save_admins(admins_data)
            log_action("demote", target_name, "junior → удалён", caller_id)
            await update.message.reply_text(f"⬇️ {target_name} лишён прав администратора.")
        else:
            await update.message.reply_text(f"ℹ️ {target_name} не является администратором.")


async def check_expirations(context):
    now_ts = datetime.now(tz=timezone.utc).timestamp()
    warns_data = load_warns()
    changed = False
    for player in warns_data:
        before = len(warns_data[player])
        warns_data[player] = [w for w in warns_data[player] if not w.get("expires") or w["expires"] > now_ts]
        if len(warns_data[player]) < before:
            changed = True
    if changed:
        save_warns(warns_data)
    bans_data = load_bans()
    to_unban = [p for p, b in bans_data.items() if b.get("expires") and b["expires"] <= now_ts]
    for player in to_unban:
        rcon_command(f"pardon {player}")
        log_action("unban", player, "Истёк срок бана", 0)
        del bans_data[player]
    if to_unban:
        save_bans(bans_data)


async def check_server(context):
    global server_was_online
    online, count, max_count, names = is_server_online()

    if online and RCON_PASSWORD:
        new_phase, _ = get_phase()
        global current_phase
        if new_phase and new_phase != current_phase:
            if current_phase is not None:
                label = PHASE_LABELS.get(new_phase, f"Фаза {new_phase}")
                subs = load_subscribers()
                for chat_id in subs:
                    try:
                        await context.bot.send_message(
                            chat_id=chat_id,
                            text=f"🧬 Фаза паразитов изменилась!\n{label}"
                        )
                    except Exception:
                        pass
            current_phase = new_phase

    if online and names:
        player_data = load_player_stats()
        now_ts = datetime.now(tz=timezone.utc).timestamp()
        kill_counts = fetch_kill_counts(names)
        for name in names:
            entry = player_data.get(name, {"count": 0, "last_seen": None, "kills": 0})
            entry["count"] = entry.get("count", 0) + 1
            entry["last_seen"] = now_ts
            if name in kill_counts:
                entry["kills"] = kill_counts[name]
            player_data[name] = entry
        save_player_stats(player_data)

    if server_was_online is None:
        server_was_online = online
        return

    if online == server_was_online:
        return

    server_was_online = online
    data = load_stats()
    now = datetime.now(tz=timezone.utc).timestamp()
    if online:
        data["last_online"] = now
    else:
        data["last_offline"] = now
    save_stats(data)

    subs = load_subscribers()
    if not subs:
        return

    if online:
        msg = (
            f"🟢 Сервер снова онлайн!\n"
            f"👥 Игроков: {count}/{max_count}\n"
            f"🌐 IP: {SERVER_IP}"
        )
    else:
        msg = f"🔴 Сервер ушёл офлайн.\n🌐 IP: {SERVER_IP}"

    for chat_id in subs:
        try:
            await context.bot.send_message(chat_id=chat_id, text=msg)
        except Exception:
            pass


def main():
    if not TOKEN:
        raise ValueError("TELEGRAM_BOT_TOKEN is not set. Please add it as a secret.")

    app = Application.builder().token(TOKEN).build()

    app.add_handler(CommandHandler("start", start))
    app.add_handler(CommandHandler("myid", myid))
    app.add_handler(CommandHandler("ip", ip))
    app.add_handler(CommandHandler("rules", rules))
    app.add_handler(CommandHandler("event", event))
    app.add_handler(CommandHandler("status", status))
    app.add_handler(CommandHandler("players", players))
    app.add_handler(CommandHandler("notify", notify))
    app.add_handler(CommandHandler("stats", stats))
    app.add_handler(CommandHandler("top", top))
    app.add_handler(CommandHandler("search", search))
    app.add_handler(CommandHandler("team", team))
    app.add_handler(CommandHandler("phase", phase))
    app.add_handler(CommandHandler("warn", warn))
    app.add_handler(CommandHandler("warns", warns))
    app.add_handler(CommandHandler("clearwarns", clearwarns))
    app.add_handler(CommandHandler("ban", ban))
    app.add_handler(CommandHandler("unban", unban))
    app.add_handler(CommandHandler("admins", admins))
    app.add_handler(CommandHandler("addadmin", addadmin))
    app.add_handler(CommandHandler("removeadmin", removeadmin))
    app.add_handler(CommandHandler("history", history))
    app.add_handler(MessageHandler(
        filters.TEXT & ~filters.COMMAND & filters.Regex(r'^(бан|варн|повысить|понизить)'),
        keyword_action
    ))

    ensure_creator_in_admins()

    app.job_queue.run_repeating(check_server, interval=CHECK_INTERVAL, first=10)
    app.job_queue.run_repeating(check_expirations, interval=30, first=15)

    print("Bot is running...")
    app.run_polling()


if __name__ == "__main__":
    main()
