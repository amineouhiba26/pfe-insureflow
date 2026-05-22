-- V10__fix_claim_dates.sql
-- All 100 synthetic claims from V9 use client UUIDs a0000001-*-00000000000{1-5}.
-- Spread them across 2024-01 to 2025-12 so the monthly chart has 24 data points.
-- Real test claims (non-synthetic client UUIDs) are not touched.
--
-- Distribution: Q1-2024=8  Q2-2024=10  Q3-2024=12  Q4-2024=15
--               Q1-2025=15 Q2-2025=15  Q3-2025=13  Q4-2025=12  → total=100

WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (ORDER BY submitted_at) AS rn
    FROM   claims
    WHERE  client_id IN (
               'a0000001-0000-0000-0000-000000000001',
               'a0000001-0000-0000-0000-000000000002',
               'a0000001-0000-0000-0000-000000000003',
               'a0000001-0000-0000-0000-000000000004',
               'a0000001-0000-0000-0000-000000000005'
           )
),
date_map AS (
    SELECT id,
           CASE rn
               -- Jan 2024 (3)
               WHEN 1   THEN '2024-01-05 09:00:00+00'::timestamptz
               WHEN 2   THEN '2024-01-14 14:30:00+00'::timestamptz
               WHEN 3   THEN '2024-01-23 11:00:00+00'::timestamptz
               -- Feb 2024 (3)
               WHEN 4   THEN '2024-02-07 08:45:00+00'::timestamptz
               WHEN 5   THEN '2024-02-15 13:20:00+00'::timestamptz
               WHEN 6   THEN '2024-02-26 16:00:00+00'::timestamptz
               -- Mar 2024 (2)
               WHEN 7   THEN '2024-03-08 10:15:00+00'::timestamptz
               WHEN 8   THEN '2024-03-21 09:30:00+00'::timestamptz
               -- Apr 2024 (3)
               WHEN 9   THEN '2024-04-03 11:00:00+00'::timestamptz
               WHEN 10  THEN '2024-04-12 15:45:00+00'::timestamptz
               WHEN 11  THEN '2024-04-24 08:20:00+00'::timestamptz
               -- May 2024 (4)
               WHEN 12  THEN '2024-05-06 12:00:00+00'::timestamptz
               WHEN 13  THEN '2024-05-11 09:00:00+00'::timestamptz
               WHEN 14  THEN '2024-05-20 14:15:00+00'::timestamptz
               WHEN 15  THEN '2024-05-29 10:30:00+00'::timestamptz
               -- Jun 2024 (3)
               WHEN 16  THEN '2024-06-04 13:00:00+00'::timestamptz
               WHEN 17  THEN '2024-06-17 08:45:00+00'::timestamptz
               WHEN 18  THEN '2024-06-28 11:20:00+00'::timestamptz
               -- Jul 2024 (4)
               WHEN 19  THEN '2024-07-03 10:00:00+00'::timestamptz
               WHEN 20  THEN '2024-07-11 14:00:00+00'::timestamptz
               WHEN 21  THEN '2024-07-19 09:30:00+00'::timestamptz
               WHEN 22  THEN '2024-07-27 16:00:00+00'::timestamptz
               -- Aug 2024 (4)
               WHEN 23  THEN '2024-08-02 11:00:00+00'::timestamptz
               WHEN 24  THEN '2024-08-10 08:30:00+00'::timestamptz
               WHEN 25  THEN '2024-08-18 13:45:00+00'::timestamptz
               WHEN 26  THEN '2024-08-28 10:00:00+00'::timestamptz
               -- Sep 2024 (4)
               WHEN 27  THEN '2024-09-05 12:00:00+00'::timestamptz
               WHEN 28  THEN '2024-09-13 09:15:00+00'::timestamptz
               WHEN 29  THEN '2024-09-21 14:30:00+00'::timestamptz
               WHEN 30  THEN '2024-09-29 08:00:00+00'::timestamptz
               -- Oct 2024 (5)
               WHEN 31  THEN '2024-10-04 10:30:00+00'::timestamptz
               WHEN 32  THEN '2024-10-10 13:00:00+00'::timestamptz
               WHEN 33  THEN '2024-10-17 09:45:00+00'::timestamptz
               WHEN 34  THEN '2024-10-23 14:00:00+00'::timestamptz
               WHEN 35  THEN '2024-10-30 11:15:00+00'::timestamptz
               -- Nov 2024 (5)
               WHEN 36  THEN '2024-11-05 08:30:00+00'::timestamptz
               WHEN 37  THEN '2024-11-11 13:45:00+00'::timestamptz
               WHEN 38  THEN '2024-11-18 10:00:00+00'::timestamptz
               WHEN 39  THEN '2024-11-24 15:30:00+00'::timestamptz
               WHEN 40  THEN '2024-11-29 09:00:00+00'::timestamptz
               -- Dec 2024 (5)
               WHEN 41  THEN '2024-12-03 11:30:00+00'::timestamptz
               WHEN 42  THEN '2024-12-09 08:15:00+00'::timestamptz
               WHEN 43  THEN '2024-12-15 14:00:00+00'::timestamptz
               WHEN 44  THEN '2024-12-20 10:45:00+00'::timestamptz
               WHEN 45  THEN '2024-12-27 13:00:00+00'::timestamptz
               -- Jan 2025 (5)
               WHEN 46  THEN '2025-01-06 09:30:00+00'::timestamptz
               WHEN 47  THEN '2025-01-13 14:00:00+00'::timestamptz
               WHEN 48  THEN '2025-01-20 10:15:00+00'::timestamptz
               WHEN 49  THEN '2025-01-27 08:45:00+00'::timestamptz
               WHEN 50  THEN '2025-01-30 15:00:00+00'::timestamptz
               -- Feb 2025 (5)
               WHEN 51  THEN '2025-02-04 11:00:00+00'::timestamptz
               WHEN 52  THEN '2025-02-10 08:30:00+00'::timestamptz
               WHEN 53  THEN '2025-02-17 13:15:00+00'::timestamptz
               WHEN 54  THEN '2025-02-21 10:00:00+00'::timestamptz
               WHEN 55  THEN '2025-02-27 14:30:00+00'::timestamptz
               -- Mar 2025 (5)
               WHEN 56  THEN '2025-03-05 09:00:00+00'::timestamptz
               WHEN 57  THEN '2025-03-11 12:30:00+00'::timestamptz
               WHEN 58  THEN '2025-03-18 08:15:00+00'::timestamptz
               WHEN 59  THEN '2025-03-24 13:45:00+00'::timestamptz
               WHEN 60  THEN '2025-03-29 10:30:00+00'::timestamptz
               -- Apr 2025 (5)
               WHEN 61  THEN '2025-04-02 11:00:00+00'::timestamptz
               WHEN 62  THEN '2025-04-09 08:45:00+00'::timestamptz
               WHEN 63  THEN '2025-04-16 14:00:00+00'::timestamptz
               WHEN 64  THEN '2025-04-22 09:30:00+00'::timestamptz
               WHEN 65  THEN '2025-04-28 13:15:00+00'::timestamptz
               -- May 2025 (5)
               WHEN 66  THEN '2025-05-05 10:00:00+00'::timestamptz
               WHEN 67  THEN '2025-05-12 08:30:00+00'::timestamptz
               WHEN 68  THEN '2025-05-19 13:45:00+00'::timestamptz
               WHEN 69  THEN '2025-05-25 11:00:00+00'::timestamptz
               WHEN 70  THEN '2025-05-30 09:15:00+00'::timestamptz
               -- Jun 2025 (5)
               WHEN 71  THEN '2025-06-04 14:00:00+00'::timestamptz
               WHEN 72  THEN '2025-06-10 08:00:00+00'::timestamptz
               WHEN 73  THEN '2025-06-17 12:30:00+00'::timestamptz
               WHEN 74  THEN '2025-06-23 10:15:00+00'::timestamptz
               WHEN 75  THEN '2025-06-30 09:00:00+00'::timestamptz
               -- Jul 2025 (4)
               WHEN 76  THEN '2025-07-04 11:30:00+00'::timestamptz
               WHEN 77  THEN '2025-07-12 08:45:00+00'::timestamptz
               WHEN 78  THEN '2025-07-21 13:00:00+00'::timestamptz
               WHEN 79  THEN '2025-07-29 10:00:00+00'::timestamptz
               -- Aug 2025 (5)
               WHEN 80  THEN '2025-08-05 09:30:00+00'::timestamptz
               WHEN 81  THEN '2025-08-11 13:15:00+00'::timestamptz
               WHEN 82  THEN '2025-08-18 08:00:00+00'::timestamptz
               WHEN 83  THEN '2025-08-24 14:30:00+00'::timestamptz
               WHEN 84  THEN '2025-08-30 10:45:00+00'::timestamptz
               -- Sep 2025 (4)
               WHEN 85  THEN '2025-09-06 11:00:00+00'::timestamptz
               WHEN 86  THEN '2025-09-15 08:30:00+00'::timestamptz
               WHEN 87  THEN '2025-09-22 13:45:00+00'::timestamptz
               WHEN 88  THEN '2025-09-29 09:15:00+00'::timestamptz
               -- Oct 2025 (4)
               WHEN 89  THEN '2025-10-03 10:00:00+00'::timestamptz
               WHEN 90  THEN '2025-10-10 14:30:00+00'::timestamptz
               WHEN 91  THEN '2025-10-19 08:45:00+00'::timestamptz
               WHEN 92  THEN '2025-10-28 12:00:00+00'::timestamptz
               -- Nov 2025 (4)
               WHEN 93  THEN '2025-11-04 09:30:00+00'::timestamptz
               WHEN 94  THEN '2025-11-12 13:00:00+00'::timestamptz
               WHEN 95  THEN '2025-11-20 08:15:00+00'::timestamptz
               WHEN 96  THEN '2025-11-27 14:45:00+00'::timestamptz
               -- Dec 2025 (4)
               WHEN 97  THEN '2025-12-03 10:30:00+00'::timestamptz
               WHEN 98  THEN '2025-12-10 08:00:00+00'::timestamptz
               WHEN 99  THEN '2025-12-18 13:15:00+00'::timestamptz
               WHEN 100 THEN '2025-12-29 11:00:00+00'::timestamptz
           END AS new_submitted_at
    FROM   ranked
)
UPDATE claims
SET    submitted_at = dm.new_submitted_at
FROM   date_map dm
WHERE  claims.id = dm.id;
