\set ON_ERROR_STOP on

-- 性能数据只用于隔离的临时数据库。账号使用系统自适应哈希格式，但负载脚本
-- 直接签发短期测试 JWT，以便测量业务查询和预约事务而非密码哈希吞吐量。
update sys_user
set must_change_password = false,
    status = 'ACTIVE',
    failed_login_count = 0,
    locked_until = null
where username in ('sysadmin01', 'labadmin01');

insert into sys_user (
    id, username, password_hash, real_name, user_type, department, email, phone,
    status, must_change_password
)
select
    20000 + number,
    'perf' || to_char(number, 'FM00000'),
    '$2a$12$8AQ6ddrNNUe5suCXpLpERuoOFJtxxcA0Hlbv8RXouCHlIwYMW2nEa',
    '性能测试学生' || number,
    'STUDENT',
    '性能测试数据',
    'perf' || number || '@example.invalid',
    '139' || lpad(number::text, 8, '0'),
    'ACTIVE',
    false
from generate_series(1, 10000) number
on conflict (id) do nothing;

insert into sys_user_role (user_id, role_id)
select 20000 + number, (select id from sys_role where code = 'STUDENT')
from generate_series(1, 10000) number
on conflict do nothing;

insert into lab (
    id, code, name, building, room_no, capacity, lab_type, description, tags,
    status, student_approval_mode, teacher_approval_mode, allow_student_booking,
    max_periods_per_user_day, advance_days, cancel_before_minutes, require_check_in
)
select
    10000 + number,
    'PERF-' || to_char(number, 'FM000'),
    '性能实验室 ' || number,
    '性能测试楼',
    to_char(number, 'FM000'),
    60,
    '性能测试',
    '隔离性能测试生成数据',
    '["Linux","Performance"]'::jsonb,
    'ACTIVE',
    'AUTO',
    'AUTO',
    true,
    4,
    60,
    0,
    false
from generate_series(1, 200) number
on conflict (id) do nothing;

insert into lab_open_rule (lab_id, day_of_week, period_no)
select 10000 + lab_number, day_number, period_number
from generate_series(1, 200) lab_number
cross join generate_series(1, 7) day_number
cross join generate_series(1, 4) period_number
on conflict do nothing;

select setval(pg_get_serial_sequence('sys_user', 'id'), (select max(id) from sys_user), true);
select setval(pg_get_serial_sequence('lab', 'id'), (select max(id) from lab), true);

analyze sys_user;
analyze lab;
analyze lab_open_rule;
