package com.faction.clientportal.dto;

import com.faction.clientportal.model.ModificationRecord;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModificationRecordDto {

    private String userId;
    private String userName;
    private LocalDateTime modifiedAt;

    public static ModificationRecordDto fromEntity(ModificationRecord r) {
        return ModificationRecordDto.builder()
                .userId(r.getUserId())
                .userName(r.getUserName())
                .modifiedAt(r.getModifiedAt())
                .build();
    }
}
