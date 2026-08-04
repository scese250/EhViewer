#[cfg(test)]
mod tests {
    use crate::parser::api::parse_vote_tag;
    use crate::parser::archive::{parse_archive_url, parse_archives_with_funds};
    use crate::parser::detail::parse_gallery_detail;
    use crate::parser::list::parse_info_list;
    use crate::parser::profile::{parse_profile, parse_profile_url};
    use reqwest::get;
    use std::fs;
    use tl::ParserOptions;

    #[tokio::test]
    async fn test_parse_info() {
        let resp = get("https://e-hentai.org/").await.expect("Failed to get!");
        let body = resp.text().await.expect("Failed to receive!");
        let dom = tl::parse(&body, ParserOptions::default()).expect("Failed to parse html");
        let result = parse_info_list(&dom, dom.parser()).expect("Failed to parse info list");
        dbg!(result);
    }

    #[test]
    fn test_parse_info_list_with_watched_tags() {
        let body =
            fs::read_to_string("test_data/gallery_list.html").expect("Failed to read html file");
        let dom = tl::parse(&body, ParserOptions::default()).expect("Failed to parse html");
        let result = parse_info_list(&dom, dom.parser()).expect("Failed to parse info list");
        assert!(!result.galleryInfoList.is_empty());
        assert!(result.prev.is_some());
        assert!(result.next.is_some());
        let first = &result.galleryInfoList[0];
        // Watched tags carry both the abbreviated display text and the site color
        assert!(!first.simpleTags.is_empty());
        assert!(!first.watchedTags.is_empty());
        assert!(
            first.watchedTags.iter().any(|t| {
                t.text == "f:ryona" && t.color.as_deref() == Some("A600FF")
            }),
            "Expected watched tag f:ryona with color A600FF, got {:?}",
            first.watchedTags,
        );
        // The gallery language is shown separately ("EN 20P"), so language tags must not
        // appear as chips even though they may be present in simpleTags
        assert!(
            result
                .galleryInfoList
                .iter()
                .any(|i| i.simpleTags.iter().any(|t| t.starts_with("language:"))),
            "Fixture should contain a language tag in simpleTags",
        );
        for info in &result.galleryInfoList {
            assert!(
                !info.watchedTags.iter().any(|t| t.text == "english" || t.text == "chinese"),
                "Language tag should be filtered from watchedTags, got {:?}",
                info.watchedTags,
            );
        }
    }

    #[test]
    fn test_parse_archives_with_funds() {
        let body = fs::read_to_string("test_data/archives_with_funds.html")
            .expect("Failed to read html file");
        let dom = tl::parse(&body, ParserOptions::default()).expect("Failed to parse html");
        let result =
            parse_archives_with_funds(&dom, dom.parser(), &body).expect("Failed to parse archives");
        dbg!(result);
    }

    #[test]
    fn test_parse_archive_url() {
        let body =
            fs::read_to_string("test_data/archive_url.html").expect("Failed to read html file");
        let dom = tl::parse(&body, ParserOptions::default()).expect("Failed to parse html");
        let result =
            parse_archive_url(&dom, dom.parser(), &body).expect("Failed to parse archives");
        assert_eq!(result, Some("https://0?start=1".to_string()));
    }

    #[tokio::test]
    async fn test_parse_profile() {
        let body =
            fs::read_to_string("test_data/profile_url.html").expect("Failed to read html file");
        let dom = tl::parse(&body, ParserOptions::default()).expect("Failed to parse html");
        let result = parse_profile_url(&dom, dom.parser()).expect("Failed to parse profile url");
        let resp = get(result).await.expect("Failed to get!");
        let body = resp.text().await.expect("Failed to receive!");
        let dom = tl::parse(&body, ParserOptions::default()).expect("Failed to parse html");
        let result = parse_profile(&dom, dom.parser()).expect("Failed to parse profile");
        assert_eq!(result.displayName, "Tenboro".to_string());
        assert_eq!(
            result.avatar,
            Some("https://forums.e-hentai.org/ehgt/jdk_180.png".to_string())
        );
    }

    #[tokio::test]
    async fn test_parse_gallery_detail() {
        let resp = get("https://e-hentai.org/g/530350/8b3c7e4a21/")
            .await
            .expect("Failed to get!");
        let body = resp.text().await.expect("Failed to receive!");
        let mut dom =
            tl::parse(&body, ParserOptions::default().track_ids()).expect("Failed to parse HTML");
        let result = parse_gallery_detail(&mut dom, &body).expect("Failed to parse gallery detail");
        dbg!(result);
    }

    #[test]
    fn test_parse_tag_gallery() {
        let json = fs::read_to_string("test_data/vote_tag.json").expect("Failed to read json file");
        let result = parse_vote_tag(&json).expect_err("Should fail to parse");
        dbg!(result);
    }
}
