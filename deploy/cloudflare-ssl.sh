#!/usr/bin/env bash
# Makes Cloudflare talk to the server over checked HTTPS ("Full (strict)") for the game's address only,
# with a Configuration Rule, so the other addresses in the zone keep their own SSL mode.
#   CF=<token> ZONE=pointsapp.pl DOMAIN=kc.pointsapp.pl deploy/cloudflare-ssl.sh apply|check
# The token needs "Config Rules: Edit" (check needs only read) for the zone.
set -euo pipefail
mode="${1:-apply}"
api=https://api.cloudflare.com/client/v4
call() { curl -sS -H "Authorization: Bearer $CF" -H "Content-Type: application/json" "$@"; }

zone="$(call "$api/zones?name=$ZONE" | jq -r '.result[0].id // empty')"
[ -n "$zone" ] || { echo "::error::The Cloudflare token cannot see the zone $ZONE"; exit 1; }

desc="Kings & Castles: SSL Full (strict) for $DOMAIN"
entry="$(call "$api/zones/$zone/rulesets/phases/http_config_settings/entrypoint")"
code="$(echo "$entry" | jq -r '.errors[0].code // empty')"
if ! echo "$entry" | jq -e '.success' >/dev/null; then
  # 10003 = no configuration rules in this zone yet; anything else is most likely a missing permission
  if [ "$code" != "10003" ]; then
    echo "::error::The Cloudflare token cannot read Configuration Rules: $(echo "$entry" | jq -c '.errors')."
    echo "Add the permission Zone → Config Rules → Edit to the token (My Profile → API Tokens → Edit)."
    exit 1
  fi
  rules='[]'
else
  rules="$(echo "$entry" | jq -c '.result.rules // []')"
fi

if echo "$rules" | jq -e --arg d "$desc" 'any(.[]; .description == $d and .action_parameters.ssl == "strict")' >/dev/null; then
  echo "ok: $DOMAIN already uses SSL Full (strict)"
  exit 0
fi
if [ "$mode" = check ]; then
  echo "info: the SSL rule for $DOMAIN is not there yet (the deploy adds it)"
  exit 0
fi

ours="$(jq -nc --arg d "$desc" --arg h "$DOMAIN" \
  '{description:$d, expression:("(http.host eq \"" + $h + "\")"), action:"set_config", action_parameters:{ssl:"strict"}, enabled:true}')"
# keep everyone else's rules as they are, replace only ours
body="$(echo "$rules" | jq -c --arg d "$desc" --argjson ours "$ours" \
  '{rules: ([.[] | select(.description != $d) | {description, expression, action, action_parameters, enabled}] + [$ours])}')"
res="$(call -X PUT "$api/zones/$zone/rulesets/phases/http_config_settings/entrypoint" --data "$body")"
if echo "$res" | jq -e '.success' >/dev/null; then
  echo "ok: $DOMAIN now uses SSL Full (strict)"
else
  echo "::error::Cloudflare did not accept the SSL rule: $(echo "$res" | jq -c '.errors')."
  echo "The token needs Zone → Config Rules → Edit (My Profile → API Tokens → Edit)."
  exit 1
fi
