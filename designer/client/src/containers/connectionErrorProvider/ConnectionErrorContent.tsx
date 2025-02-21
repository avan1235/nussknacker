import React, { FC } from "react";
import { Box, SvgIconTypeMap, Typography } from "@mui/material";
import { OverridableComponent } from "@mui/material/OverridableComponent";
import { useMuiTheme } from "../useMuiTheme";

interface Props {
    Icon: OverridableComponent<SvgIconTypeMap>;
    headerText: string;
    contentText: string;
}

export const ConnectionErrorContent: FC<Props> = ({ Icon, headerText, contentText }) => {
    const theme = useMuiTheme();

    return (
        <Box
            display={"flex"}
            alignItems={"center"}
            flexDirection={"column"}
            p={4}
            sx={{ backgroundColor: theme.colors.secondaryBackground }}
        >
            <Icon sx={{ width: "56px", height: "56px", fill: "white" }} />
            <Typography mb={2} color={"white"} variant={"h5"}>
                {headerText}
            </Typography>
            <Typography align={"center"} color={"white"} variant={"body2"}>
                {contentText}
            </Typography>
        </Box>
    );
};
