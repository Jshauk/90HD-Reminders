#!/bin/bash
echo "=== Testing 90HD API ==="

echo -e "\n--- Health Check ---"
curl -s http://localhost:8090/api/health

echo -e "\n\n--- Create Reminder ---"
curl -s -X POST http://localhost:8090/api/reminders \
  -H 'Content-Type: application/json' \
  -d '{"text":"Test reminder","frequency_value":1,"frequency_unit":"days","start_time":"08:00"}'

echo -e "\n\n--- List Reminders ---"
curl -s http://localhost:8090/api/reminders

echo -e "\n\n--- Get Settings ---"
curl -s http://localhost:8090/api/settings

echo -e "\n\n--- Sync ---"
curl -s http://localhost:8090/api/sync

echo -e "\n\n--- Update Reminder 1 ---"
curl -s -X PUT http://localhost:8090/api/reminders/1 \
  -H 'Content-Type: application/json' \
  -d '{"text":"Updated reminder","active_hours_start":"09:00","active_hours_end":"21:00"}'

echo -e "\n\n--- Delete Reminder 1 ---"
curl -s -X DELETE http://localhost:8090/api/reminders/1 -w "\nHTTP Status: %{http_code}"

echo -e "\n\n--- List (should be empty) ---"
curl -s http://localhost:8090/api/reminders

echo -e "\n\n=== Done ==="
