-- companyType holds an industry for every synthetic posting except these two, which said "스타트업" (a company size).
UPDATE job_postings SET company_type = '클라우드'
WHERE seed_key IN ('onfit-001', 'onfit-002') AND company_type = '스타트업';
