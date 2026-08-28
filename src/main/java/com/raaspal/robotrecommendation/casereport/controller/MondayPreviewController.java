package com.raaspal.robotrecommendation.casereport.controller;

import com.raaspal.robotrecommendation.casereport.adapters.monday.MondayBoardReader;
import com.raaspal.robotrecommendation.casereport.adapters.monday.dto.MondayItem;
import com.raaspal.robotrecommendation.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Temporary endpoint for eyeballing what the monday sync actually pulls back.
 * It exists to prove the adapter end to end before the snapshot tables and the
 * report generators are written, and should be removed once the console screen
 * replaces it.
 */
@RestController
@RequestMapping("/api/v1/case-reports/monday")
@RequiredArgsConstructor
public class MondayPreviewController {

    private final MondayBoardReader boardReader;

    /**
     * Reads one board group live.
     *
     * <p>Cleaning Tickets / All Case:
     * {@code ?boardId=3451717331&groupId=new_group96592__1&columnIds=text0,text6,status_17,date8,status,status7}
     *
     * <p>Delivery Tickets / All Case:
     * {@code ?boardId=1647612496&groupId=group_title&columnIds=tags42,text6,tags2,asset_owner,status_139,date5,status,status_1}
     */
    @GetMapping("/preview")
    public ApiResponse<List<MondayItem>> preview(
            @RequestParam String boardId,
            @RequestParam String groupId,
            @RequestParam(required = false) List<String> columnIds) {
        return ApiResponse.success(
                boardReader.readGroupItems(boardId, groupId, columnIds == null ? List.of() : columnIds));
    }
}
