import json
import os
import re
from pathlib import Path
from yt_dlp import YoutubeDL

def download_video(url, directory):
    if not re.match(r"^https://(?:www\\.|m\\.)?(?:youtube\\.com|youtu\\.be)/", url):
        raise ValueError("Нужна HTTPS-ссылка YouTube")
    os.makedirs(directory, exist_ok=True)
    options = {
        "format": "best[ext=mp4][vcodec!=none][acodec!=none]/best[vcodec!=none][acodec!=none]",
        "outtmpl": os.path.join(directory, "%(id)s.%(ext)s"),
        "noplaylist": True,
        "quiet": True,
        "no_warnings": True,
        "retries": 3,
        "fragment_retries": 3,
        "socket_timeout": 30,
        "restrictfilenames": True,
        "overwrites": False,
    }
    with YoutubeDL(options) as ydl:
        info = ydl.extract_info(url, download=True)
        if not info:
            raise RuntimeError("Не удалось получить информацию о видео")
        file_path = None
        for entry in info.get("requested_downloads", []):
            candidate = entry.get("filepath")
            if candidate and os.path.isfile(candidate):
                file_path = candidate
                break
        if not file_path:
            candidate = ydl.prepare_filename(info)
            if os.path.isfile(candidate):
                file_path = candidate
        if not file_path:
            raise RuntimeError("Видео скачано, но файл не найден")
        if not file_path.lower().endswith(".mp4"):
            raise RuntimeError("YouTube предоставил не MP4; выбери другой ролик или формат")
        return json.dumps({
            "path": file_path,
            "title": info.get("title") or info.get("id") or "Видео",
            "channel": info.get("channel") or info.get("uploader") or "YouTube",
            "date": info.get("upload_date") or "",
            "description": info.get("description") or "",
        }, ensure_ascii=False)
