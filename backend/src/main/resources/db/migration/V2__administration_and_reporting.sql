create table user_import_task (
    id uuid primary key,
    created_by bigint not null references sys_user(id),
    file_name varchar(255) not null,
    status varchar(20) not null check (status in ('PROCESSING', 'COMPLETED', 'FAILED')),
    total_rows integer not null default 0 check (total_rows >= 0),
    succeeded_rows integer not null default 0 check (succeeded_rows >= 0),
    failed_rows integer not null default 0 check (failed_rows >= 0),
    error_summary varchar(2000),
    created_at timestamptz not null default now(),
    completed_at timestamptz
);

create index idx_user_import_task_creator_created
    on user_import_task (created_by, created_at desc);
