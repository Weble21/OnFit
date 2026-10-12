"""Read-only source probe: no backend authentication, DB imports, or email delivery."""
import argparse
import itertools
import json
import os
from pathlib import Path

from collect import Http, expand_companies, saramin, source_budget

ADAPTERS = {"saramin": saramin}
REQUIRED = {
    "saramin": ["ONFIT_SARAMIN_API_KEY", "ONFIT_SARAMIN_PERMISSION_EVIDENCE"],
}
ALLOWED_ENV = set(itertools.chain.from_iterable(REQUIRED.values())) | {
    "ONFIT_SARAMIN_REQUEST_BUDGET",
    "ONFIT_COLLECTOR_LOOKBACK_HOURS",
}


def load_environment(path):
    """Read only source settings from a local ignored .env, without overwriting exported settings."""
    file = Path(path)
    if not file.exists():
        return
    for number, raw in enumerate(file.read_text(encoding="utf-8-sig").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            raise ValueError(f"Invalid environment entry on line {number}")
        name, value = line.split("=", 1)
        name, value = name.strip(), value.strip()
        if name not in ALLOWED_ENV:
            continue
        if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
            value = value[1:-1]
        os.environ.setdefault(name, value)


def probe(source, companies, pages=1, budget=16, factory=Http):
    missing = [name for name in REQUIRED[source] if not os.environ.get(name, "").strip()]
    if missing:
        return {"source": source, "state": "not_configured", "missingSettings": missing, "requests": 0}
    report = {"imported": 0, "incomplete": 0, "skipped": 0}
    result = {"source": source, "state": "failed", "requests": 0, "collected": 0, "samples": []}
    http = None
    try:
        http = factory(limit=min(budget, source_budget(source)))
        for item in ADAPTERS[source](http, companies, pages, report):
            result["collected"] += 1
            if len(result["samples"]) < 5:
                content = item["content"]
                # Never print response bodies, keys, URLs with query strings, or contact details.
                result["samples"].append({"company": content["companyName"], "title": content["title"],
                                          "location": content["location"], "status": content["status"],
                                          "deadline": content.get("deadline")})
        result["state"] = "partial" if report["incomplete"] or report["skipped"] else (
            "ok" if result["collected"] else "no_matches")
    except Exception as error:
        result["errorType"] = type(error).__name__
        result["httpStatus"] = getattr(error, "code", None)
    result.update(requests=http.count if http is not None else 0,
                  incomplete=report["incomplete"], skipped=report["skipped"])
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--company", action="append", required=True, help="Repeat to probe several companies; groups expand automatically")
    parser.add_argument("--source", choices=list(ADAPTERS), default="saramin")
    parser.add_argument("--env-file", default=str(Path(__file__).resolve().parent.parent / ".env"))
    parser.add_argument("--pages", type=int, default=1)
    parser.add_argument("--request-budget", type=int, default=16)
    args = parser.parse_args(argv)
    if not 1 <= args.pages <= 3 or not 1 <= args.request_budget <= 50:
        parser.error("pages must be 1..3; request-budget must be 1..50")
    companies = expand_companies(args.company)
    if not companies or len(companies) > 50:
        parser.error("Use 1..50 expanded company names")
    try:
        load_environment(args.env_file)
    except Exception as error:
        print(json.dumps({"readOnly": True, "state": "failed", "errorType": type(error).__name__}))
        return 1
    sources = [args.source]
    results = [probe(source, companies, args.pages, args.request_budget) for source in sources]
    print(json.dumps({"readOnly": True, "companies": companies, "results": results}, ensure_ascii=True, indent=2))
    # Empty results prove an API response, not that company coverage or imports work.
    if any(result["state"] == "not_configured" for result in results):
        return 2
    return 0 if all(result["state"] in ("ok", "no_matches") for result in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
