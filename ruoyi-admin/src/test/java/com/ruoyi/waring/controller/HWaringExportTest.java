package com.ruoyi.waring.controller;

import com.github.pagehelper.PageHelper;
import com.ruoyi.common.core.domain.entity.SysDept;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.system.mapper.SysDeptMapper;
import com.ruoyi.system.mapper.SysUserMapper;
import com.ruoyi.waring.domain.HWaring;
import com.ruoyi.waring.mapper.HWaringMapper;
import com.ruoyi.waring.service.impl.HWaringServiceImpl;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HWaringExportTest {
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        HWaringMapper mapper = mock(HWaringMapper.class);
        when(mapper.selectWaringList(any())).thenReturn(List.of(warning("ordinary")));
        when(mapper.selectWubaoList(any())).thenAnswer(call -> {
            assertNull(PageHelper.getLocalPage(), "exports must not start pagination");
            return List.of(warning("false-alarm"));
        });
        when(mapper.selectReconditionList(any())).thenAnswer(call -> {
            assertNull(PageHelper.getLocalPage(), "exports must not start pagination");
            return List.of(warning("recondition"));
        });
        SysUser user = new SysUser();
        user.setDeptId(100L);
        SysDept dept = new SysDept();
        dept.setOrgIndex("10");
        SysUserMapper users = mock(SysUserMapper.class);
        SysDeptMapper departments = mock(SysDeptMapper.class);
        when(users.selectUserById(1L)).thenReturn(user);
        when(departments.selectDeptById(100L)).thenReturn(dept);
        HWaringServiceImpl service = new HWaringServiceImpl();
        ReflectionTestUtils.setField(service, "hWaringMapper", mapper);
        ReflectionTestUtils.setField(service, "userMapper", users);
        ReflectionTestUtils.setField(service, "sysDeptMapper", departments);
        HWaringController controller = new HWaringController() {
            @Override
            public Long getUserId() { return 1L; }
        };
        ReflectionTestUtils.setField(controller, "hWaringService", service);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void falseAlarmExportContainsOnlyFalseAlarms() throws Exception {
        assertExport("falseAlarm", "false-alarm");
    }

    @Test
    void reconditionExportContainsOnlyReconditionRecords() throws Exception {
        assertExport("recondition", "recondition");
    }

    @Test
    void ordinaryExportKeepsExistingScope() throws Exception {
        assertExport("all", "ordinary");
    }

    private void assertExport(String scope, String expectedName) throws Exception {
        byte[] data = mvc.perform(post("/waring/waring/importTemplate")
                .param("exportScope", scope).param("pageNum", "1").param("pageSize", "1"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            assertTrue(workbook.getSheetAt(0).getRow(1).cellIterator().hasNext());
            StringBuilder values = new StringBuilder();
            workbook.getSheetAt(0).getRow(1).forEach(cell -> values.append(cell.toString()));
            assertTrue(values.toString().contains(expectedName), values.toString());
        }
    }

    private HWaring warning(String name) {
        HWaring warning = new HWaring();
        warning.setW_id(1);
        warning.setAlarm_type_name(name);
        return warning;
    }
}
