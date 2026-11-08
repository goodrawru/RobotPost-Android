import json
import os
from urllib.parse import urlparse
from yt_dlp import YoutubeDL

def _check_url(url):
    url = url.strip()
    parsed = urlparse(url)
    if parsed.scheme != "https" or (parsed.hostname or "").lower() not in (
        "youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be", "music.youtube.com"
    ):
        raise ValueError("Нужна ссылка https://youtube.com или https://youtu.be")
    return url

def _options():
    return {
        "noplaylist": True, "quiet": True, "no_warnings": True,
        "retries": 4, "fragment_retries": 4, "socket_timeout": 35,
        "extractor_args": {"youtube": {"player_client": ["android", "web"]}},
    }

def _available(info):
    formats = [f for f in (info.get("formats") or []) if f.get("url")]
    combined = [f for f in formats if f.get("ext") == "mp4"
                and f.get("vcodec") not in (None, "none")
                and f.get("acodec") not in (None, "none")]
    video = [f for f in formats if f.get("ext") == "mp4"
             and f.get("vcodec", "none").startswith(("avc1", "h264"))
             and f.get("acodec") == "none"]
    audio = [f for f in formats if f.get("ext") == "m4a"
             and f.get("acodec") not in (None, "none")]
    best_audio = max(audio, key=lambda f: f.get("abr") or f.get("tbr") or 0, default=None)
    choices = []
    for f in combined:
        choices.append({"id": "single:" + str(f["format_id"]),
                        "label": str(f.get("height") or "?") + "p · MP4 со звуком",
                        "height": f.get("height") or 0, "kind": "single"})
    if best_audio:
        for f in video:
            choices.append({"id": "merge:" + str(f["format_id"]) + "+" + str(best_audio["format_id"]),
                            "label": str(f.get("height") or "?") + "p · MP4 + M4A (объединение)",
                            "height": f.get("height") or 0, "kind": "merge"})
        choices.append({"id": "audio:" + str(best_audio["format_id"]),
                        "label": "Только аудио · M4A", "height": -1, "kind": "audio"})
    choices.sort(key=lambda c: (c["height"], c["kind"] == "single"), reverse=True)
    return choices

def inspect_video(url):
    with YoutubeDL(_options()) as ydl:
        info = ydl.extract_info(_check_url(url), download=False)
    if not info:
        raise RuntimeError("Не удалось получить сведения о видео")
    choices = _available(info)
    return json.dumps({
        "title": info.get("title") or "Видео",
        "channel": info.get("channel") or info.get("uploader") or "YouTube",
        "formats": choices,
    }, ensure_ascii=False)

def _get_file(info, ydl):
    for item in info.get("requested_downloads") or []:
        path = item.get("filepath")
        if path and os.path.isfile(path):
            return path
    path = ydl.prepare_filename(info)
    if os.path.isfile(path):
        return path
    raise RuntimeError("Файл не найден после скачивания")

def _fetch(url, directory, fmt_id, prefix):
    opts = _options()
    opts.update({"format": fmt_id, "outtmpl": os.path.join(directory, prefix + ".%(ext)s"),
                 "overwrites": True, "restrictfilenames": True})
    with YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=True)
        return info, _get_file(info, ydl)

def download_video(url, directory, choice):
    url = _check_url(url)
    os.makedirs(directory, exist_ok=True)
    with YoutubeDL(_options()) as ydl:
        info = ydl.extract_info(url, download=False)
    valid = {item["id"] for item in _available(info)}
    if choice not in valid:
        raise ValueError("Формат изменился. Обнови список форматов.")
    kind, fmt = choice.split(":", 1)
    paths = []
    if kind == "merge":
        video_id, audio_id = fmt.split("+", 1)
        _, video = _fetch(url, directory, video_id, "video")
        _, audio = _fetch(url, directory, audio_id, "audio")
        paths = [video, audio]
    elif kind == "audio":
        _, audio = _fetch(url, directory, fmt, "audio")
        paths = ["", audio]
    else:
        _, video = _fetch(url, directory, fmt, "video")
        paths = [video, ""]
    return json.dumps({
        "video": paths[0], "audio": paths[1], "merge": kind == "merge",
        "title": info.get("title") or info.get("id") or "Видео",
        "channel": info.get("channel") or info.get("uploader") or "YouTube",
        "date": info.get("upload_date") or "",
        "description": info.get("description") or "",
    }, ensure_ascii=False)
