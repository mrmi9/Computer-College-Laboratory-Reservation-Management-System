insert into sys_user (
    id, username, password_hash, real_name, user_type, department, email, phone,
    status, must_change_password
) values
    (1001, 'student01', '$2a$12$8AQ6ddrNNUe5suCXpLpERuoOFJtxxcA0Hlbv8RXouCHlIwYMW2nEa', '演示学生', 'STUDENT', '计算机学院', 'student01@example.invalid', '13800000001', 'ACTIVE', true),
    (1002, 'teacher01', '$2a$12$8AQ6ddrNNUe5suCXpLpERuoOFJtxxcA0Hlbv8RXouCHlIwYMW2nEa', '演示教师', 'TEACHER', '计算机学院', 'teacher01@example.invalid', '13800000002', 'ACTIVE', true),
    (1003, 'labadmin01', '$2a$12$8AQ6ddrNNUe5suCXpLpERuoOFJtxxcA0Hlbv8RXouCHlIwYMW2nEa', '实验室管理员', 'STAFF', '实验中心', 'labadmin01@example.invalid', '13800000003', 'ACTIVE', true),
    (1004, 'sysadmin01', '$2a$12$8AQ6ddrNNUe5suCXpLpERuoOFJtxxcA0Hlbv8RXouCHlIwYMW2nEa', '系统管理员', 'STAFF', '信息化办公室', 'sysadmin01@example.invalid', '13800000004', 'ACTIVE', true)
on conflict (id) do nothing;

insert into sys_user_role (user_id, role_id) values
    (1001, 1), (1002, 2), (1003, 3), (1004, 4)
on conflict do nothing;

insert into lab (
    id, code, name, building, room_no, capacity, lab_type, description,
    responsible_user_id, tags, student_approval_mode, teacher_approval_mode
) values
    (101, 'LAB-A301', '人工智能实验室', '计算机楼', 'A301', 60, '专业实验室', '配备 GPU 工作站，适合人工智能课程与科研。', 1003, '["GPU", "Linux", "CUDA"]', 'MANUAL', 'AUTO'),
    (102, 'LAB-A302', '软件工程实验室', '计算机楼', 'A302', 48, '通用机房', '适合软件工程、数据库和网络课程。', 1003, '["Windows", "Linux", "数据库"]', 'MANUAL', 'MANUAL')
on conflict (id) do nothing;

insert into lab_manager (lab_id, user_id) values (101, 1003), (102, 1003)
on conflict do nothing;

insert into lab_open_rule (lab_id, day_of_week, period_no)
select lab_id, day_no, period_no
from (values (101), (102)) labs(lab_id)
cross join generate_series(1, 5) day_no
cross join generate_series(1, 4) period_no
on conflict do nothing;

insert into equipment (
    id, lab_id, asset_code, category, name, model, total_quantity, required_qualification, status
) values
    (201, 101, 'GPU-A301', '计算设备', 'GPU 工作站', 'RTX 5090', 20, 'GPU_LAB_TRAINING', 'AVAILABLE'),
    (202, 101, 'VR-A301', '交互设备', 'VR 头显', 'Campus VR', 10, null, 'AVAILABLE'),
    (203, 102, 'PC-A302', '计算设备', '学生电脑', 'Campus Desktop', 48, null, 'AVAILABLE')
on conflict (id) do nothing;

insert into user_qualification (user_id, qualification_code, valid_until, granted_by)
values (1002, 'GPU_LAB_TRAINING', current_date + 365, 1004)
on conflict do nothing;

select setval(pg_get_serial_sequence('sys_user', 'id'), (select max(id) from sys_user), true);
select setval(pg_get_serial_sequence('lab', 'id'), (select max(id) from lab), true);
select setval(pg_get_serial_sequence('equipment', 'id'), (select max(id) from equipment), true);
