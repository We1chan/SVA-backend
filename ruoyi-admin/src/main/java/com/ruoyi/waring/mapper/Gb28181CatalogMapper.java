package com.ruoyi.waring.mapper;

import com.ruoyi.waring.domain.Gb28181Channel;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Mapper
@Repository
public interface Gb28181CatalogMapper {
    int upsertCatalogChannel(Gb28181Channel channel);

    List<Gb28181Channel> selectChannelsByZlmServerId(Long zlmServerId);

    int updateMediaAvailability(Long id, boolean available);
}
