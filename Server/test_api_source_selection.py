import os
import unittest

os.environ.setdefault("OPENAI_API_KEY", "test-key")

import server
import vibe_factory


class ApiSourceSelectionTests(unittest.TestCase):
    def test_google_weather_docs_are_not_treated_as_no_key_public_api(self):
        source = {
            "title": "Weather API Current Conditions",
            "url": "https://developers.google.com/maps/documentation/weather/current-conditions",
            "final_url": "https://developers.google.com/maps/documentation/weather/current-conditions",
            "quality_passed": True,
            "page_result": {
                "status": "success",
                "final_url": "https://developers.google.com/maps/documentation/weather/current-conditions",
                "text_content": "Current conditions endpoint documentation.",
            },
        }

        self.assertTrue(server._source_has_auth_requirement(source))
        self.assertIsNone(server._best_public_no_key_api_candidate([source]))

    def test_open_meteo_weather_candidate_wins_public_no_key_selection(self):
        google_source = {
            "title": "Google Weather API",
            "url": "https://weather.googleapis.com/v1/currentConditions:lookup",
            "final_url": "https://weather.googleapis.com/v1/currentConditions:lookup",
            "quality_passed": True,
            "confidence": 0.99,
            "source_type": "official_docs",
            "source_kind": "api_fetch",
            "page_result": {
                "status": "success",
                "final_url": "https://weather.googleapis.com/v1/currentConditions:lookup",
                "text_content": "Weather API docs.",
            },
        }
        open_meteo_source = server._build_open_meteo_weather_candidate(
            "서울 현재 날씨 무료 공개 API no key"
        )

        selected = server._best_public_no_key_api_candidate([google_source, open_meteo_source])

        self.assertIs(selected, open_meteo_source)
        self.assertFalse(server._source_has_auth_requirement(open_meteo_source))
        self.assertIn("open-meteo.com", selected["final_url"])

    def test_weather_no_key_strategy_appends_open_meteo_candidate_once(self):
        candidates = []
        strategy = {
            "prefer_public_no_key": True,
            "public_api_search_query": "서울 날씨 무료 공개 API no key official documentation",
        }

        server._append_deterministic_public_no_key_candidates(
            candidates,
            "서울 현재 날씨 API",
            strategy,
        )
        server._append_deterministic_public_no_key_candidates(
            candidates,
            "서울 현재 날씨 API",
            strategy,
        )

        self.assertEqual(len(candidates), 1)
        self.assertIn("open-meteo.com", candidates[0]["final_url"])

    def test_common_key_based_api_patterns_are_not_no_key_candidates(self):
        sources = [
            {
                "title": "RapidAPI docs",
                "url": "https://rapidapi.com/example/weather",
                "quality_passed": True,
            },
            {
                "title": "News API endpoint",
                "url": "https://newsapi.org/v2/top-headlines?apiKey=demo",
                "quality_passed": True,
            },
            {
                "title": "Exchange API",
                "url": "https://api.example.com/latest?access_key=demo",
                "quality_passed": True,
            },
            {
                "title": "Auth docs",
                "url": "https://developer.example.com/docs",
                "quality_passed": True,
                "page_result": {
                    "text_content": "Requests require an API key in the x-api-key header.",
                },
            },
        ]

        self.assertTrue(all(server._source_has_auth_requirement(source) for source in sources))
        self.assertIsNone(server._best_public_no_key_api_candidate(sources))

    def test_select_best_api_source_prefers_candidate_matching_scope_constraints(self):
        build_spec = vibe_factory.normalize_runtime_build_spec(
            {
                "app_goal": "강원대학교 춘천 캠퍼스 학식당 메뉴를 읽어와 알림을 주는 앱",
                "data_source_type": "web_scrape",
            }
        )
        samcheok_source = {
            "title": "강원대학교 삼척캠퍼스 학생식당 메뉴",
            "url": "https://www.kangwon.ac.kr/samcheok/menu",
            "final_url": "https://www.kangwon.ac.kr/samcheok/menu",
            "snippet": "삼척캠퍼스 학생식당 오늘 식단",
            "text_content": "강원대학교 삼척캠퍼스 학생식당 중식 메뉴 안내",
            "source_type": "official_docs",
            "source_kind": "generic_fetch",
            "quality_passed": True,
            "confidence": 0.9,
        }
        chuncheon_source = {
            "title": "강원대학교 춘천캠퍼스 학생식당 메뉴",
            "url": "https://www.kangwon.ac.kr/chuncheon/menu",
            "final_url": "https://www.kangwon.ac.kr/chuncheon/menu",
            "snippet": "춘천캠퍼스 학생식당 오늘 식단",
            "text_content": "강원대학교 춘천캠퍼스 학생식당 중식 메뉴 안내",
            "source_type": "official_docs",
            "source_kind": "generic_fetch",
            "quality_passed": True,
            "confidence": 0.85,
        }

        selection = server.select_best_api_source(
            "강원대학교 춘천 캠퍼스 학식당 메뉴",
            [samcheok_source, chuncheon_source],
            build_spec=build_spec,
        )

        self.assertEqual(selection["selected_source"]["final_url"], chuncheon_source["final_url"])
        rejected_reasons = [item["reason"] for item in selection["rejected_sources"]]
        self.assertTrue(any("source_constraints_unmatched" in reason for reason in rejected_reasons))

    def test_web_data_contract_minimum_samples_follow_observed_samples(self):
        merged = server._merge_web_data_contracts(
            {
                "source_kind": "static_html_text",
                "parser_strategy": "text_pattern",
                "minimum_sample_records": 10,
            },
            {
                "source_kind": "static_html_links",
                "parser_strategy": "html_link_list",
                "minimum_sample_records": 1,
                "sample_records": [
                    {
                        "title": "새로운 API 출시와 개발자 도구 업데이트",
                        "url": "https://news.hada.io/topic?id=12345",
                    }
                ],
            },
        )

        self.assertEqual(merged["minimum_sample_records"], 1)
        self.assertEqual(len(merged["sample_records"]), 1)
        self.assertIsInstance(merged["sample_records"][0], dict)


if __name__ == "__main__":
    unittest.main()
