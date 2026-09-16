#!/usr/bin/env python3
# log_server.py — servidor de logs de la instrumentación "sin root" de
# compil-apk-termux (ver modulos/apk.sh, cmd_instrument "logstart"). Fuente:
# VictorH028/no-root-logger (https://github.com/VictorH028/no-root-logger),
# portado tal cual salvo la ruta del log (absoluta, junto al propio script,
# en vez de relativa al cwd — apk.sh lo arranca vía tmux y no controla desde
# qué directorio corre).

import json
import logging
import os
import socket
from typing import Optional


HOST = "127.0.0.1"
PORT = 9999

MAX_HEADERS = 16 * 1024
MAX_BODY = 1024 * 1024

LOG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "app_logs.txt")


# level=logging.DEBUG (2026-09-12, confirmado en dispositivo real):
# el proyecto original usaba level=logging.INFO acá, pero RemoteLogger.hookEnter/hookExit/d
# (RemoteLogger.java) mandan SIEMPRE level="DEBUG" — Python descarta cualquier logging.debug()
# cuando el logger está en INFO (DEBUG < INFO en la jerarquía de logging), así que el archivo
# de log quedaba SIEMPRE vacío pese a que el servidor respondía "200 OK" a cada request (el
# bug era silencioso: la petición HTTP se procesaba bien, solo el registro en disco se perdía).
# Reproducido con un POST manual real antes del fix (respuesta 200 OK, app_logs.txt seguía en
# 0 bytes) y confirmado resuelto después.
logging.basicConfig(
    filename=LOG_FILE,
    level=logging.DEBUG,
    format="%(asctime)s - %(levelname)s - %(message)s",
)


def send_response(
    conn: socket.socket,
    status: str,
    body: bytes = b"",
) -> None:
    response = (
        f"HTTP/1.1 {status}\r\n"
        "Content-Type: text/plain; charset=utf-8\r\n"
        f"Content-Length: {len(body)}\r\n"
        "Connection: close\r\n"
        "\r\n"
    ).encode("ascii") + body

    conn.sendall(response)


def read_request(conn: socket.socket) -> tuple[str, str, bytes]:
    data = b""

    while b"\r\n\r\n" not in data:
        chunk = conn.recv(4096)

        if not chunk:
            raise ValueError("Conexión cerrada")

        data += chunk

        if len(data) > MAX_HEADERS:
            raise ValueError("Cabeceras HTTP demasiado grandes")

    headers, body = data.split(b"\r\n\r\n", 1)

    lines = headers.decode("latin-1").split("\r\n")

    if not lines:
        raise ValueError("Petición HTTP vacía")

    request_line = lines[0].split()

    if len(request_line) != 3:
        raise ValueError("Línea HTTP inválida")

    method, path, version = request_line

    if method != "POST":
        raise ValueError("Método no permitido")

    if path != "/log":
        raise ValueError("Ruta no permitida")

    if version not in ("HTTP/1.0", "HTTP/1.1"):
        raise ValueError("Versión HTTP no soportada")

    content_length: Optional[int] = None

    for line in lines[1:]:
        if ":" not in line:
            continue

        name, value = line.split(":", 1)

        if name.lower() == "content-length":
            try:
                content_length = int(value.strip())
            except ValueError:
                raise ValueError("Content-Length inválido")

            break

    if content_length is None:
        raise ValueError("Falta Content-Length")

    if content_length < 0:
        raise ValueError("Content-Length negativo")

    if content_length > MAX_BODY:
        raise ValueError("Cuerpo demasiado grande")

    while len(body) < content_length:
        chunk = conn.recv(4096)

        if not chunk:
            raise ValueError("Cuerpo HTTP incompleto")

        body += chunk

    return method, path, body[:content_length]


def handle_client(conn: socket.socket, addr) -> None:
    try:
        _, _, body = read_request(conn)

        log_data = json.loads(body.decode("utf-8"))

        if not isinstance(log_data, dict):
            raise ValueError("El JSON debe ser un objeto")

        level = str(log_data.get("level", "INFO"))
        tag = str(log_data.get("tag", "APP"))
        message = str(log_data.get("message", ""))

        level = level[:100]
        tag = tag[:500]
        message = message[:MAX_BODY]

        log_entry = f"[{tag}] {message}"

        if level == "ERROR":
            logging.error(log_entry)
        elif level == "WARNING":
            logging.warning(log_entry)
        elif level == "DEBUG":
            logging.debug(log_entry)
        else:
            logging.info(log_entry)

        send_response(conn, "200 OK", b"OK")

        print(f"[{level}] [{tag}] {message}")

    except Exception as exc:
        logging.error("Error procesando petición: %s", exc)

        print(f"Error de {addr}: {exc}")

        try:
            send_response(conn, "400 Bad Request")
        except Exception:
            pass

    finally:
        conn.close()


def start_server() -> None:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.setsockopt(
            socket.SOL_SOCKET,
            socket.SO_REUSEADDR,
            1,
        )

        server.bind((HOST, PORT))
        server.listen(5)

        print(f"Servidor de logs en {HOST}:{PORT} -> {LOG_FILE}")

        while True:
            conn, addr = server.accept()

            handle_client(conn, addr)


if __name__ == "__main__":
    start_server()
