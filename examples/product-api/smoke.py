#!/usr/bin/env python3
"""별도 JVM 두 개와 실제 HTTP로 REDIS-04를 검증한다. 먼저 Maven compile/copy-dependencies 실행."""
import json
import os
from pathlib import Path
import select
import subprocess
import time
import urllib.error
import urllib.request

PROJECT = Path(__file__).resolve().parents[2] / "mini-redis"
JAVA = str(Path(os.environ["JAVA_HOME"]) / "bin/java")
CP = f"{PROJECT}/target/classes:{PROJECT}/target/dependency/*"


def start(main, *args):
    proc = subprocess.Popen([JAVA, "-cp", CP, main, *map(str, args)], stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, text=True)
    try:
        if not select.select([proc.stdout], [], [], 8)[0]:
            raise AssertionError("server startup timed out")
        line = proc.stdout.readline().strip()
        assert "listening on http://127.0.0.1:" in line, line
        return proc, line.rsplit(" ", 1)[1]
    except BaseException:
        proc.kill()
        proc.communicate(timeout=8)
        raise


def request(base, path, method="GET", body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=3) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()


def main():
    processes = []
    try:
        redis, cache_url = start("miniredis.Main", 0, 100)
        processes.append(redis)
        product, product_url = start("miniredis.product.ProductMain", 0, cache_url, 30000, 0)
        processes.append(product)
        first = request(product_url, "/products/42")
        assert first[0] == 200
        assert request(product_url, "/products/42") == first
        def reads():
            return json.loads(request(product_url, "/stats")[1])["originReads"]
        assert reads() == 1
        # Explicitly expire the cached value, then verify a fresh origin read.
        assert request(cache_url, "/keys/product:42", "PUT", {"value": first[1], "ttlMs": 1})[0] == 204
        time.sleep(0.02)
        assert request(product_url, "/products/42") == first
        assert reads() == 2
        assert request(cache_url, "/keys/product:42", "DELETE")[0] == 204
        assert request(product_url, "/products/42") == first
        assert reads() == 3
        assert request(product_url, "/products/42", "PUT", {"name": "배"})[0] == 204
        assert request(cache_url, "/keys/product:42")[0] == 404
        assert json.loads(request(product_url, "/products/42")[1])["name"] == "배"
        assert reads() == 4
        redis.kill()
        redis.wait(timeout=8)
        assert json.loads(request(product_url, "/products/42")[1])["name"] == "배"
        assert reads() == 5
        print(json.dumps({"result": "PASS", "separateJvms": 2, "originReads": reads(),
                          "checks": ["miss", "hit", "ttl", "delete", "update-invalidation", "SIGKILL-fallback"]}))
    finally:
        for proc in reversed(processes):
            if proc.poll() is None:
                proc.terminate()
            try:
                _, err = proc.communicate(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()
                _, err = proc.communicate(timeout=5)
            if err:
                print(err)


if __name__ == "__main__":
    main()
