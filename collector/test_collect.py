import json
import os
import time
import unittest
import urllib.error
import urllib.parse
import xml.etree.ElementTree as ET
from unittest.mock import patch
from collect import Http, saramin_map, wanted_map, source_url, saramin, jobkorea, wanted, run, company_name

SARAMIN_ENV = {'ONFIT_SARAMIN_API_KEY': 'fixture', 'ONFIT_SARAMIN_PERMISSION_EVIDENCE': 'fixture'}
WANTED_ENV = {'ONFIT_WANTED_CLIENT_ID': 'fixture', 'ONFIT_WANTED_CLIENT_SECRET': 'fixture', 'ONFIT_WANTED_PERMISSION_EVIDENCE': 'fixture'}
BACKEND_ENV = {'ONFIT_BACKEND_URL': 'https://backend.test', 'ONFIT_OIDC_ISSUER': 'https://issuer.test',
               'ONFIT_COLLECTOR_CLIENT_ID': 'fixture', 'ONFIT_COLLECTOR_CLIENT_SECRET': 'fixture'}


def report():
    return {'imported': 0, 'incomplete': 0, 'skipped': 0}


def saramin_job(job_id, company, updated, location='Seoul'):
    return (f'<job><id>{job_id}</id><url>https://www.saramin.co.kr/zf_user/jobs/relay/view?rec_idx={job_id}</url>'
            f'<active>1</active><company><name>{company}</name></company><position><title>Backend</title>'
            f'<job-code>Backend</job-code><location>{location}</location></position>'
            f'<modification-timestamp>{int(updated)}</modification-timestamp></job>')


class FakeHttp(Http):
    """Real budget accounting; fetch answers from a handler instead of the network."""
    def __init__(self, handler, limit=450):
        super().__init__(limit)
        self.handler = handler
        self.urls = []

    def fetch(self, request, timeout):
        self.urls.append(request.full_url)
        value = self.handler(request.full_url)
        return value.encode() if isinstance(value, str) else json.dumps(value).encode()


def param(url, name):
    return urllib.parse.parse_qs(urllib.parse.urlsplit(url).query)[name][0]


class CollectorTests(unittest.TestCase):
    def test_saramin_preserves_attribution_and_never_invents_skills(self):
        job = ET.fromstring('''<job><id>123</id><url>http://www.saramin.co.kr/zf_user/jobs/relay/view?rec_idx=123&amp;utm_source=api</url>
          <active>1</active><company><name>(주)Example</name></company><position><title>Java developer</title>
          <job-code>Backend</job-code><location>Seoul</location></position><keyword>Java,SQL</keyword></job>''')
        value = saramin_map(job)
        self.assertIn('utm_source=api', value['sourceUrl'])
        self.assertTrue(value['sourceUrl'].startswith('https://'))
        self.assertEqual([], value['content']['requiredSkills'])
        self.assertIsNone(value['content']['deadline'])
        self.assertEqual('Example', value['content']['companyName'])

    def test_company_names_drop_only_legal_forms(self):
        for raw in ['(주)Example', '㈜ Example', 'Example 주식회사', '( 주 )Example', 'Example(유)']:
            self.assertEqual('Example', company_name(raw))
        self.assertEqual('Example Bank', company_name('(주) Example  Bank'))

    def test_wanted_maps_separate_requirement_text_and_closed_state(self):
        value = wanted_map(dict(id=12, url='https://www.wanted.co.kr/wd/12', status='close', due_time='2026-12-01',
            company=dict(name='Example'), address=dict(location='Seoul'),
            detail=dict(position='Backend', main_tasks='Build APIs', requirements='Java or Kotlin', preferred_points='SQL')),
            dict(name='Backend'))
        self.assertEqual('CLOSED', value['content']['status'])
        self.assertIn('Java or Kotlin', value['content']['description'])
        self.assertEqual([], value['content']['requiredSkills'])

    def test_source_urls_reject_foreign_hosts_and_credentials(self):
        for url in ['https://evil.test/wd/1', 'https://www.wanted.co.kr.evil.test/wd/1',
                    'https://user:password@www.wanted.co.kr/wd/1', 'https://www.wanted.co.kr:8000/wd/1']:
            with self.assertRaises(ValueError): source_url('wanted', url)

    def test_missing_approvals_never_make_requests(self):
        class NeverHttp:
            def call(self, *args, **kwargs): raise AssertionError('Unexpected request')
        with patch.dict(os.environ, {}, clear=True):
            for adapter in [saramin, wanted, jobkorea]:
                with self.assertRaises(ValueError): list(adapter(NeverHttp(), ['Example'], 3, report()))

    def test_saramin_company_over_page_budget_skips_only_that_company(self):
        now = time.time()
        def handler(url):
            if param(url, 'keywords') == 'Big':
                jobs = ''.join(saramin_job(f'big{param(url, "start")}{i}', '(주)Big', now) for i in range(3))
                return f'<job-search><jobs total="900">{jobs}</jobs></job-search>'
            return f'<job-search><jobs total="1">{saramin_job("small", "Small", now)}</jobs></job-search>'
        http, result = FakeHttp(handler), report()
        with patch.dict(os.environ, SARAMIN_ENV), patch('collect.time.sleep'):
            items = list(saramin(http, ['Big', 'Small'], 2, result))
        self.assertEqual(1, result['incomplete'])
        self.assertEqual(2, sum(param(url, 'keywords') == 'Big' for url in http.urls))
        self.assertEqual(['Big'] * 6 + ['Small'], [item['content']['companyName'] for item in items])

    def test_saramin_stops_paging_at_postings_older_than_the_lookback(self):
        now = time.time()
        jobs = saramin_job('new', 'Example', now - 3600) + saramin_job('old', 'Example', now - 100 * 3600)
        http, result = FakeHttp(lambda url: f'<job-search><jobs total="5000">{jobs}</jobs></job-search>'), report()
        with patch.dict(os.environ, {**SARAMIN_ENV, 'ONFIT_COLLECTOR_LOOKBACK_HOURS': '72'}), patch('collect.time.sleep'):
            items = list(saramin(http, ['Example'], 3, result))
        self.assertEqual(['new'], [item['externalId'] for item in items])
        self.assertEqual(1, len(http.urls))
        self.assertEqual(0, result['incomplete'])

    def test_one_malformed_posting_is_skipped_not_the_source(self):
        now = time.time()
        jobs = saramin_job('bad', 'Example', now, location='') + saramin_job('good', 'Example', now)
        http, result = FakeHttp(lambda url: f'<job-search><jobs total="2">{jobs}</jobs></job-search>'), report()
        with patch.dict(os.environ, SARAMIN_ENV):
            items = list(saramin(http, ['Example'], 3, result))
        self.assertEqual(['good'], [item['externalId'] for item in items])
        self.assertEqual(1, result['skipped'])

    def test_missing_modification_time_does_not_use_old_publication_time_to_stop(self):
        now = time.time()
        missing = ET.fromstring(saramin_job('unknown', 'Example', now))
        missing.remove(missing.find('modification-timestamp'))
        ET.SubElement(missing, 'posting-timestamp').text = str(int(now - 100 * 3600))
        jobs = ET.tostring(missing, encoding='unicode') + saramin_job('recent', 'Example', now)
        http, result = FakeHttp(lambda url: f'<job-search><jobs total="2">{jobs}</jobs></job-search>'), report()
        with patch.dict(os.environ, SARAMIN_ENV):
            items = list(saramin(http, ['Example'], 3, result))
        self.assertEqual(['unknown', 'recent'], [item['externalId'] for item in items])
        self.assertEqual(0, result['incomplete'])

    def test_missing_posting_id_is_skipped_before_backend_import(self):
        now = time.time()
        missing = ET.fromstring(saramin_job('bad', 'Example', now))
        missing.remove(missing.find('id'))
        jobs = ET.tostring(missing, encoding='unicode') + saramin_job('good', 'Example', now)
        http, result = FakeHttp(lambda url: f'<job-search><jobs total="2">{jobs}</jobs></job-search>'), report()
        with patch.dict(os.environ, SARAMIN_ENV):
            items = list(saramin(http, ['Example'], 3, result))
        self.assertEqual(['good'], [item['externalId'] for item in items])
        self.assertEqual(1, result['skipped'])

    def test_wanted_request_budget_is_shared_fairly_between_companies(self):
        def handler(url):
            path = urllib.parse.urlsplit(url).path
            if path.endswith('/search/company'):
                name = param(url, 'query')
                return {'data': [{'id': 1 if name == 'Busy' else 2, 'name': name}], 'links': {}}
            if path.endswith('/jobs') and '/companies/' in path:
                company_id = path.split('/')[-2]
                return {'jobs': [{'id': int(company_id) * 100 + i, 'name': 'Backend'} for i in range(20)], 'links': {}}
            job_id = int(path.rsplit('/', 1)[1])
            if job_id == 201:
                raise urllib.error.HTTPError(url, 404, 'gone', {}, None)
            name = 'Busy' if job_id < 200 else 'Quiet'
            return dict(id=job_id, url=f'https://www.wanted.co.kr/wd/{job_id}', status='active',
                        company=dict(name=name), address=dict(location='Seoul'), detail=dict(position='Backend', main_tasks='APIs'))
        http, result = FakeHttp(handler, limit=8), report()
        with patch.dict(os.environ, WANTED_ENV), patch('collect.time.sleep'):
            items = list(wanted(http, ['Busy', 'Quiet'], 3, result))
        names = [item['content']['companyName'] for item in items]
        # Busy gets 4 of 8 requests (search, list, two details); Quiet still gets the other 4.
        self.assertEqual(['Busy', 'Busy', 'Quiet'], names)
        self.assertEqual(8, http.count)
        self.assertEqual({'imported': 0, 'incomplete': 2, 'skipped': 1}, result)

    def test_each_source_gets_its_own_request_budget(self):
        budgets = {}
        def adapter(name):
            def collect(http, companies, pages, result):
                budgets[name] = http.limit
                self.assertEqual(['Example'], companies)
                return iter([])
            return collect
        def backend(url, **kwargs):
            if url.endswith('/token'): return {'access_token': 'fixture'}
            if url.endswith('/targets'): return ['(주)Example', 'Example']
            return {'remainingSubscriptions': 0, 'unknownEmails': 0}
        backend_http = FakeHttp(lambda url: None)
        backend_http.call = lambda url, **kwargs: backend(url, **kwargs)
        with patch.dict(os.environ, {**BACKEND_ENV, 'ONFIT_WANTED_REQUEST_BUDGET': '40'}, clear=True), \
                patch('collect.Http', side_effect=lambda limit: backend_http if limit == 3000 else Http(limit)), \
                patch('collect.saramin', adapter('saramin')), patch('collect.wanted', adapter('wanted')), \
                patch('collect.jobkorea', adapter('jobkorea')), patch('builtins.print'):
            self.assertEqual(0, run())
        self.assertEqual({'saramin': 150, 'wanted': 40, 'jobkorea': 150}, budgets)

    def test_source_failure_or_partial_still_runs_other_sources_and_notifications(self):
        class BackendHttp:
            def call(self, url, **kwargs):
                if url.endswith('/token'): return {'access_token':'fixture'}
                if url.endswith('/targets'): return ['Example', 'Example']
                if '/alerts/run?' in url: return {'remainingSubscriptions':0, 'unknownEmails':0}
                raise AssertionError(url)
        def partial(http, companies, pages, result):
            result['incomplete'] += 1
            return iter([])
        with patch.dict(os.environ, BACKEND_ENV, clear=True), \
                patch('collect.saramin', side_effect=ValueError('secret MUST NOT BE LOGGED')), \
                patch('collect.wanted', partial), patch('collect.jobkorea', return_value=iter([])), \
                patch('builtins.print') as output:
            self.assertEqual(1, run(BackendHttp()))
        logged = [json.loads(call.args[0]) for call in output.call_args_list]
        self.assertNotIn('MUST NOT BE LOGGED', str(logged))
        self.assertEqual(['failed', 'partial', 'ok'], [line['state'] for line in logged if 'source' in line])
        self.assertTrue(any('notifications' in line for line in logged))


if __name__ == '__main__': unittest.main()
