import json
import os
import time
import unittest
import urllib.parse
import xml.etree.ElementTree as ET
from unittest.mock import patch
from collect import Http, saramin_map, source_url, saramin, run, company_name, expand_companies

SARAMIN_ENV = {'ONFIT_SARAMIN_API_KEY': 'fixture', 'ONFIT_SARAMIN_PERMISSION_EVIDENCE': 'fixture'}
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
    def test_group_expansion_is_shared_and_child_interest_stays_specific(self):
        self.assertEqual(['카카오', '카카오페이', '카카오뱅크', '카카오모빌리티'], expand_companies(['(주)카카오', '카카오페이']))
        self.assertEqual(['카카오페이'], expand_companies(['카카오페이']))

    def test_saramin_collects_each_related_company_after_expansion(self):
        now = time.time()
        def handler(url):
            company = param(url, 'keywords')
            jobs = saramin_job(company, company, now) + saramin_job('unrelated', '카카오와 일하는 개발회사', now)
            return f'<job-search><jobs total="2">{jobs}</jobs></job-search>'
        http, result = FakeHttp(handler), report()
        with patch.dict(os.environ, SARAMIN_ENV):
            items = list(saramin(http, expand_companies(['카카오']), 3, result))
        self.assertEqual(['카카오', '카카오페이', '카카오뱅크', '카카오모빌리티'], [item['content']['companyName'] for item in items])
        self.assertEqual(4, len(http.urls))

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


    def test_source_urls_reject_foreign_hosts_and_credentials(self):
        for url in ['https://evil.test/wd/1', 'https://www.saramin.co.kr.evil.test/wd/1',
                    'https://user:password@www.saramin.co.kr/wd/1', 'https://www.saramin.co.kr:8000/wd/1']:
            with self.assertRaises(ValueError): source_url('saramin', url)

    def test_missing_approvals_never_make_requests(self):
        class NeverHttp:
            def call(self, *args, **kwargs): raise AssertionError('Unexpected request')
        with patch.dict(os.environ, {}, clear=True):
            for adapter in [saramin]:
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


if __name__ == '__main__': unittest.main()
