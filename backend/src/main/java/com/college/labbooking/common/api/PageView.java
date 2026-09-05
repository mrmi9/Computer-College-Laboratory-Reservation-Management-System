package com.college.labbooking.common.api;

import java.util.List;

public record PageView<T>(List<T> items, int page, int size, long total) {}
