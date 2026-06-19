package com.buukle.agent.instance.dtvo.dto;

import lombok.Data;

import java.util.List;

@Data
public class TodowriteResultDTO {
    private List<TodoItemDTO> todos;
}
