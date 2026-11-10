import threading
import time
from concurrent.futures import ThreadPoolExecutor
import os

import requests

BASE_URL = "http://localhost:8080"
API_KEY = os.environ["GATEWAY_API_KEY"]
NUM_REQUESTS = 20
# A new question every run, so it is never already cached
QUESTION = f"In one sentence, what is a cache stampede? (test {int(time.time())})"


def llm_call_count():
    response = requests.get(f"{BASE_URL}/actuator/metrics/gateway.llm.calls")
    for measurement in response.json()["measurements"]:
        if measurement["statistic"] == "COUNT":
            return int(measurement["value"])


# All threads wait here, then start at the same moment
start_line = threading.Barrier(NUM_REQUESTS)


def send_request(request_number):
    start_line.wait()
    start = time.time()
    response = requests.post(f"{BASE_URL}/v1/ask", json={"question": QUESTION}, headers={"X-API-Key": API_KEY})
    time_ms = int((time.time() - start) * 1000)
    source = response.json().get("source") if response.ok else f"error {response.status_code}"
    return request_number, response.status_code, source, time_ms


before = llm_call_count()

with ThreadPoolExecutor(max_workers=NUM_REQUESTS) as pool:
    results = list(pool.map(send_request, range(1, NUM_REQUESTS + 1)))

after = llm_call_count()

for request_number, status, source, time_ms in results:
    print(f"Request {request_number:2}: status {status}, source {source}, {time_ms} ms")

print(f"\nAI calls made for {NUM_REQUESTS} identical requests: {after - before}")