"""Bounded official-API collection. No user email/profile is sent to this runner."""
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timezone, timedelta

MAX_BYTES = 2_000_000
HOSTS = {"saramin": "www.saramin.co.kr", "wanted": "www.wanted.co.kr", "jobkorea": "www.jobkorea.co.kr"}
# Per-source request budgets. Saramin allows 500 calls a day: two scheduled runs plus one manual run fit.
BUDGETS = {"saramin": 150, "wanted": 300, "jobkorea": 150}
# Legal-form markers sources add to the same company name: "(주)카카오", "카카오 주식회사".
LEGAL_FORMS = re.compile(r"\(\s*(?:주|유|사|재|株)\s*\)|㈜|주식회사|유한책임회사|유한회사")


class Incomplete(Exception):
    """One company could not be fully covered; the source keeps collecting the other companies."""


class BudgetExceeded(Incomplete):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # Never forward API keys/bearer tokens to a redirected host.


class Http:
    def __init__(self, limit=450):
        self.opener = urllib.request.build_opener(NoRedirect)
        self.count = 0
        self.limit = limit
        self.cap = limit

    def share(self, companies_left):
        # Each remaining company gets an equal part of what is left; requests one company leaves unused roll over.
        self.cap = self.count + (self.limit - self.count) // max(1, companies_left)

    def call(self, url, *, headers=None, payload=None, form=None, xml=False, timeout=20):
        if urllib.parse.urlsplit(url).scheme != "https":
            raise ValueError("HTTPS required")
        # Hard bound: even accidental pagination/target expansion terminates.
        if self.count >= self.cap:
            raise BudgetExceeded("Request budget exceeded")
        self.count += 1
        body = None
        hdr = {"Accept": "application/json", "User-Agent": "OnFit-approved-collector/1.0"}
        hdr.update(headers or {})
        if payload is not None:
            body = json.dumps(payload, ensure_ascii=False).encode()
            hdr["Content-Type"] = "application/json"
        if form is not None:
            body = urllib.parse.urlencode(form).encode()
            hdr["Content-Type"] = "application/x-www-form-urlencoded"
        # No blind POST retries: import/SMTP outcomes can be uncertain after a network failure.
        data = self.fetch(urllib.request.Request(url, data=body, headers=hdr), timeout)
        if len(data) > MAX_BYTES:
            raise ValueError("Response too large")
        if xml:
            if b"<!DOCTYPE" in data.upper() or b"<!ENTITY" in data.upper():
                raise ValueError("Unsafe XML")
            return ET.fromstring(data)
        return json.loads(data)

    def fetch(self, request, timeout):
        with self.opener.open(request, timeout=timeout) as response:
            return response.read(MAX_BYTES + 1)


def required_env(name):
    value = os.environ.get(name, "").strip()
    if not value:
        raise ValueError(f"Missing setting: {name}")
    return value


def query(url, **parameters):
    return url + "?" + urllib.parse.urlencode(parameters)


def observed():
    return datetime.now(timezone.utc).isoformat()


def company_name(value):
    return re.sub(r"\s+", " ", LEGAL_FORMS.sub(" ", value)).strip()


def company_key(value):
    return company_name(value).casefold()


def lookback_since():
    hours = int(os.environ.get("ONFIT_COLLECTOR_LOOKBACK_HOURS", "72"))
    if not 1 <= hours <= 720:
        raise ValueError("Lookback must be 1..720 hours")
    return (datetime.now(timezone.utc) - timedelta(hours=hours)).timestamp()


def collect(http, companies, company_jobs, report):
    """Runs company_jobs per company; running out of pages or requests skips only that company."""
    for index, company in enumerate(companies):
        http.share(len(companies) - index)
        try:
            yield from company_jobs(company)
        except Incomplete:
            report["incomplete"] += 1


def mapped(report, mapper, *values):
    # One malformed posting is skipped and counted; a wrong response shape still fails the source.
    try:
        return mapper(*values)
    except (ValueError, KeyError, TypeError, AttributeError):
        report["skipped"] += 1
        return None


def source_url(source, value):
    parts = urllib.parse.urlsplit(value)
    if parts.scheme not in ("http", "https") or parts.hostname != HOSTS[source] or parts.username or parts.password or parts.port not in (None, 443):
        raise ValueError("Unexpected source URL")
    # Preserve provider attribution parameters. Never fetch this URL from the runner.
    return urllib.parse.urlunsplit(("https", parts.netloc, parts.path, parts.query, ""))


def content(company, title, role, location, duties, *, description=None, deadline=None, status="OPEN", career=None, industry=None):
    if any(not isinstance(v, str) or not v.strip() for v in (company, title, role, location, duties)) or not company_name(company):
        raise ValueError("Missing job fields")
    return dict(companyName=company_name(company)[:200], title=title[:200], roleName=role[:120], location=location[:200],
                responsibilities=duties[:30000], description=(description or duties)[:30000],
                deadline=deadline, status=status, careerLevel=career, industry=industry,
                companySize=None, requiredSkills=[], preferredSkills=[])


def saramin_map(job):
    text = lambda path: (job.findtext(path) or "").strip()
    title = text("position/title")
    if not text("id"):
        raise ValueError("Missing Saramin posting ID")
    if text("active") not in ("0", "1"):
        raise ValueError("Unexpected Saramin posting state")
    deadline = None
    if job.find("close-type") is not None and job.find("close-type").get("code") == "1":
        deadline = datetime.fromtimestamp(int(text("expiration-timestamp")), timezone.utc).astimezone(
            timezone(timedelta(hours=9))).date().isoformat()
    # This API supplies metadata, not the full requirements. Do not fabricate mandatory skills.
    metadata = "\n".join(filter(None, [title, text("position/job-code"), text("keyword")]))
    return dict(sourceName="saramin", externalId=text("id"), sourceUrl=source_url("saramin", text("url")),
                observedAt=observed(), content=content(text("company/name"), title, text("position/job-code") or title,
                    text("position/location"), metadata, deadline=deadline,
                    status="OPEN" if text("active") == "1" else "CLOSED", career=text("position/experience-level") or None,
                    industry=text("position/industry") or None))


def saramin_updated_before(job, since):
    # sort=ud orders by modification time. Publication time cannot safely stop this scan:
    # an old posting may have just been updated, and later results may also be recent.
    value = (job.findtext("modification-timestamp") or "").strip()
    return value.isdigit() and int(value) < since


def saramin(http, companies, pages, report):
    key = required_env("ONFIT_SARAMIN_API_KEY")
    required_env("ONFIT_SARAMIN_PERMISSION_EVIDENCE")
    since = lookback_since()

    def company_jobs(company):
        target = company_key(company)
        for page in range(pages):
            root = http.call(query("https://oapi.saramin.co.kr/job-search", **{
                "access-key": key, "keywords": company, "start": page, "count": 110, "sort": "ud"}),
                headers={"Accept": "application/xml"}, xml=True)
            jobs = root.find("jobs")
            if jobs is None:
                raise ValueError("Unexpected Saramin response")
            older = False
            for job in jobs.findall("job"):
                # Newest update first: past the lookback, earlier runs already imported the rest.
                if saramin_updated_before(job, since):
                    older = True
                    break
                item = mapped(report, saramin_map, job)
                if item and company_key(item["content"]["companyName"]) == target:
                    yield item
            if older or (page + 1) * 110 >= int(jobs.get("total", "0")):
                return
            if page == pages - 1:
                raise Incomplete("Saramin coverage exceeds page budget")
            time.sleep(1)

    yield from collect(http, companies, company_jobs, report)


def wanted_map(job, listing):
    detail = job["detail"]
    title = detail.get("position") or detail.get("name") or listing["name"]
    duties = detail.get("main_tasks") or ""
    description = "\n\n".join(str(detail.get(k) or "") for k in ("intro", "main_tasks", "requirements", "preferred_points"))
    deadline = job.get("due_time") or None
    if deadline:
        datetime.strptime(deadline, "%Y-%m-%d")
    state = job.get("status")
    if state not in ("active", "archived", "close"):
        raise ValueError("Unsupported Wanted job state")
    return dict(sourceName="wanted", externalId=str(job["id"]), sourceUrl=source_url("wanted", job["url"]),
                observedAt=observed(), content=content(job["company"]["name"], title, title,
                    job["address"].get("full_location") or job["address"].get("location"), duties,
                    description=description, deadline=deadline, status="OPEN" if state == "active" else "CLOSED",
                    career=f"경력 {job.get('annual_from', 0)}~{job.get('annual_to', 100)}년"))


def wanted(http, companies, pages, report):
    required_env("ONFIT_WANTED_PERMISSION_EVIDENCE")
    headers = {"wanted-client-id": required_env("ONFIT_WANTED_CLIENT_ID"),
               "wanted-client-secret": required_env("ONFIT_WANTED_CLIENT_SECRET")}
    base = "https://openapi.wanted.jobs/v1"

    def company_ids(company, target):
        for search_page in range(pages):
            found = http.call(query(base + "/search/company", query=company, offset=search_page * 20, limit=20), headers=headers)
            # The official schema uses data; its published example uses companies.
            matches = found.get("data", found.get("companies"))
            if not isinstance(matches, list):
                raise ValueError("Unexpected Wanted company response")
            ids = [int(m["id"]) for m in matches if company_key(str(m.get("name", ""))) == target]
            if ids:
                return list(dict.fromkeys(ids))
            if not found.get("links", {}).get("next"):
                return []
        raise Incomplete("Wanted company search exceeds page budget")

    def company_jobs(company):
        target = company_key(company)
        for company_id in company_ids(company, target):
            for page in range(pages):
                result = http.call(query(f"{base}/companies/{company_id}/jobs", offset=page * 20, limit=20), headers=headers)
                if not isinstance(result.get("jobs"), list):
                    raise ValueError("Unexpected Wanted jobs response")
                for listing in result["jobs"]:
                    try:
                        job = http.call(f"{base}/jobs/{int(listing['id'])}", headers=headers)
                    except urllib.error.HTTPError as error:
                        if error.code != 404:
                            raise
                        report["skipped"] += 1  # Removed between the list and the detail call.
                        continue
                    item = mapped(report, wanted_map, job, listing)
                    if item and company_key(item["content"]["companyName"]) == target:
                        yield item
                    time.sleep(1)
                if not result.get("links", {}).get("next"):
                    break
                if page == pages - 1:
                    raise Incomplete("Wanted job coverage exceeds page budget")

    yield from collect(http, companies, company_jobs, report)


def jobkorea_map(item):
    item["sourceUrl"] = source_url("jobkorea", item["sourceUrl"])
    item["observedAt"] = observed()
    name = company_name(item["content"]["companyName"])
    if not name:
        raise ValueError("Missing job fields")
    item["content"]["companyName"] = name[:200]
    return item


def jobkorea(http, companies, pages, report):
    """Partner bridge contract, not a guessed JobKorea public endpoint.

    Approved partner returns {items: [JobImportRequest], hasNext: bool}; page starts at 0.
    Until the partner URL/permission is configured, this source explicitly fails closed.
    """
    required_env("ONFIT_JOBKOREA_PERMISSION_EVIDENCE")
    url = required_env("ONFIT_JOBKOREA_FEED_URL")
    headers = {"Authorization": "Bearer " + required_env("ONFIT_JOBKOREA_FEED_TOKEN")}

    def company_jobs(company):
        target = company_key(company)
        for page in range(pages):
            result = http.call(query(url, company=company, page=page, size=100), headers=headers)
            items = result.get("items")
            if not isinstance(items, list) or len(items) > 100 or not isinstance(result.get("hasNext"), bool):
                raise ValueError("Unexpected partner feed contract")
            for item in items:
                if item.get("sourceName") != "jobkorea":
                    raise ValueError("Partner source mismatch")
                item = mapped(report, jobkorea_map, item)
                if item and company_key(item["content"]["companyName"]) == target:
                    yield item
            if not result["hasNext"]:
                return
            if page == pages - 1:
                raise Incomplete("Partner coverage exceeds page budget")

    yield from collect(http, companies, company_jobs, report)


def source_budget(name):
    value = int(os.environ.get(f"ONFIT_{name.upper()}_REQUEST_BUDGET", BUDGETS[name]))
    if not 1 <= value <= 1000:
        raise ValueError("Source request budget must be 1..1000")
    return value


def run(http=None):
    backend_http = http or Http(limit=3000)
    base = required_env("ONFIT_BACKEND_URL").rstrip("/")
    issuer = required_env("ONFIT_OIDC_ISSUER").rstrip("/")
    client_id = required_env("ONFIT_COLLECTOR_CLIENT_ID")
    client_secret = required_env("ONFIT_COLLECTOR_CLIENT_SECRET")
    token = ""
    expires = 0
    def backend(path, **options):
        nonlocal token, expires
        if time.monotonic() >= expires:
            auth = backend_http.call(issuer + "/protocol/openid-connect/token", form={
                "grant_type": "client_credentials", "client_id": client_id,
                "client_secret": client_secret, "scope": "jobs:import jobs:notify"})
            token = auth["access_token"]
            expires = time.monotonic() + max(1, int(auth.get("expires_in", 60)) - 30)
        return backend_http.call(base + path, headers={"Authorization": "Bearer " + token}, **options)
    companies = backend("/api/admin/alerts/targets")
    if not isinstance(companies, list) or any(not isinstance(c, str) or not c.strip() for c in companies):
        raise ValueError("Invalid collection targets")
    # "(주)카카오" and "카카오" are one target; the name without the legal form is searched.
    companies = sorted({company_key(c): company_name(c) for c in companies if company_name(c)}.values())
    if len(companies) > 50:
        # Surface incomplete coverage rather than silently dropping the last subscribers.
        raise ValueError("Target budget exceeded; partition collection before enabling more companies")
    if not companies:
        print(json.dumps({"state": "no_subscriptions"}))
        return 0
    pages = int(os.environ.get("ONFIT_COLLECTOR_MAX_PAGES", "3"))
    if not 1 <= pages <= 10:
        raise ValueError("Page budget must be 1..10")
    failures = 0
    for name, adapter in (("saramin", saramin), ("wanted", wanted), ("jobkorea", jobkorea)):
        report = {"imported": 0, "incomplete": 0, "skipped": 0}
        try:
            # Separate budgets: one source using up its requests never starves the next one.
            for item in adapter(http or Http(limit=source_budget(name)), companies, pages, report):
                backend("/api/admin/jobs/import", payload=item)
                report["imported"] += 1
            partial = bool(report["incomplete"] or report["skipped"])
            # Partial coverage is not reported as success; notifications still run.
            failures += partial
            print(json.dumps({"source": name, "state": "partial" if partial else "ok", **report}))
        except Exception as error:
            failures += 1
            # Exceptions can contain URLs with keys; only log safe types/status codes.
            print(json.dumps({"source": name, "state": "failed", **report,
                              "type": type(error).__name__, "status": getattr(error, "code", None)}))
    cutoff = observed()
    unknown = 0
    for batch in range(20):
        result = backend(query("/api/admin/alerts/run", before=cutoff), payload={}, timeout=900)
        print(json.dumps({"notificationBatch": batch + 1, "notifications": result}))
        unknown += result.get("unknownEmails", 0)
        if not result.get("remainingSubscriptions", 0):
            break
        if not result.get("evaluated", 0) or batch == 19:
            raise ValueError("Notification budget exceeded or stalled; next run resumes remaining subscriptions")
    return 1 if failures or unknown else 0


if __name__ == "__main__":
    try:
        sys.exit(run())
    except Exception as error:
        print(json.dumps({"state": "failed", "type": type(error).__name__, "status": getattr(error, "code", None)}))
        sys.exit(1)
